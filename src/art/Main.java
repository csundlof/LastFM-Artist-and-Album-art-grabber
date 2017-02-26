package art;

import java.awt.Image;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import javax.imageio.ImageIO;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.audio.AudioParser;
import org.apache.tika.parser.mp3.Mp3Parser;
import org.apache.tika.parser.mp4.MP4Parser;
import org.gagravarr.tika.FlacParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;
public class Main extends Thread {

	private static final String host = "http://ws.audioscrobbler.com/2.0/", API_KEY = "1467cbdbeb8e49fab49312b7e236944a";
	private static Queue<File> folders = new LinkedList<File>();
	private static int NUMWORKERS = Runtime.getRuntime().availableProcessors();
	private static final Lock _mutex = new ReentrantLock(true);
	private final Lock artistLock = new ReentrantLock(true), albumLock = new ReentrantLock(true), artistListLock = new ReentrantLock(true);
	private static int artistsDownloaded = 0;
	private static int albumsDownloaded = 0;
	private HashMap<String, Image> artistImages = new HashMap<String, Image>(); //store already downloaded images 
	private static HashMap<String,Integer> problematicFolders = new HashMap<String,Integer>();
	private final String autocorrect = "autocorrect=1";
	private static final String API_STRING = "&api_key=" + API_KEY;
	private static boolean thorough = true;

	private FilenameFilter filter2 = (dir, name) -> {
		String lowercaseName = name.toLowerCase();
		if (lowercaseName.matches("(artist\\.(png|jpg))|(cover\\.(jpg|png))")) {
			return true;
		} else {
			return false;
		}
	};

	private FilenameFilter filter = (dir, name) -> {
		String lowercaseName = name.toLowerCase();
		if (lowercaseName.matches("(.+mp3)|(.+flac)|(.+wav)|(.+m4a)")) {
			return true;
		} else {
			return false;
		}
	};

	/**
	 * Arguments should be full paths to folders to search OR the flag -f, indicating that the program should be "sloppy", but fast. -f will make the program 
	 * search less extensively, and possibly resulting in fewer pictures being found.
	 * @param args Folders to search recursively. Make sure to use " " to encapsulate paths if they contain spaces.
	 */
	public static void main(String[] args) {
		String path = "";
		if(args.length == 0){
			Path currentRelativePath = Paths.get("");
			String s = currentRelativePath.toAbsolutePath().toString();
			path = s;
			new Main().traverseFolder(new File(path), -1, true);
		}
		else{
			for(String s : args){
				if(s.equals("-f"))
					thorough = false;
				else{
					path = s;
					System.out.println("Root folder: " + path);
					new Main().traverseFolder(new File(path), -1, true);
					NUMWORKERS += 2*Runtime.getRuntime().availableProcessors(); // more hard drives means we need more threads if we want to make good use of the I/O speed.
				}
			}
		}
		if(NUMWORKERS > Runtime.getRuntime().availableProcessors() * 9) // let's not use TOO many threads..
			NUMWORKERS = Runtime.getRuntime().availableProcessors() * 9;
		System.out.println("Program will run using " + NUMWORKERS + " threads");
		System.out.println(thorough == true ? "Fast mode is NOT enabled. Runtime will be longer, but more pictures should be found." : "Fast mode is enabled. Some pictures may not be found.");
		long time = System.currentTimeMillis();
		Thread threads[] = new Thread[NUMWORKERS];
		for(int i = 0; i < NUMWORKERS; ++i){
			threads[i] = new Thread(new Main());
			threads[i].start();
		}
		for(int i = 0; i < threads.length; ++i)
			try {
				threads[i].join();
			} catch (InterruptedException e) {}
		System.out.println("Downloaded:\n" + artistsDownloaded + " artist pictures.\n" + albumsDownloaded + " album covers.");
		System.out.println("Total time elapsed: " + ((float)(System.currentTimeMillis() - time)/1000) + " seconds");
		if(problematicFolders.size() > 0)
			System.out.println("Issues downloading in the following folders(" + problematicFolders.size() + "): ");
		for(Map.Entry<String, Integer> entry : problematicFolders.entrySet())
		{
			System.out.println(entry.getKey() + ", tried " + entry.getValue() + (entry.getValue() > 1 ? " times":" time"));
		}

	}

	/**
	 * Worker method.
	 */
	public void run() {
		Long id = this.getId();
		System.out.println("Thread " + id + " started.");
		while(!folders.isEmpty()){
			File folder = null;
			_mutex.lock();
			if(folders.isEmpty()){
				_mutex.unlock();
				break;
			}
			//if(folders.size() > NUMWORKERS * 5)
			folder = folders.remove(); // grab work from bag of tasks
			_mutex.unlock();
			if(folder != null){
				if(folders.size()%10 == 0)
					System.out.println(folders.size() + " folders remaining.");
				traverseFolder(folder, id, false);
			}
		}
		System.out.println(id + " finished.");
	}

	/**
	 * Searches deep until no more folders are found. Downloads images belonging to any songs regardless of their level.
	 * If there are too many folders in the folder, they are added to the bag of task and the recursion is interrupted.
	 * Recursion also never happens if once is set to true.
	 * @param folder
	 * @param id
	 * @param once
	 */
	private void traverseFolder(File folder, long id, boolean once)
	{
		//System.out.println("hey, I'm looking at " + folder.getName() + ". t. " + id);
		File[] directories = folder.listFiles(File::isDirectory);
		if(directories.length >= Runtime.getRuntime().availableProcessors() || once) // this folder has many subfolders, let's split the work up.
		{
			_mutex.lock();
			for(File f : directories)
			{
				folders.add(f);
			}
			_mutex.unlock();
			System.out.println("Added " + directories.length + " folders to bag of tasks.");
		}
		else{
			for(File f : directories)
			{
				traverseFolder(f, id, false);
			}
		}
		downloadImages(folder, id);
	}

	/**
	 * Downloads images in the folder. If there are any to download.
	 * @param folder
	 * @param id
	 */
	private void downloadImages(File folder, long id) {
		boolean art = false, alb = false;
		for(File f : folder.listFiles(filter2))
		{
			if(f.getName().toLowerCase().startsWith("artist")){
				art = true;
			}
			else if(f.getName().toLowerCase().startsWith("cover"))
				alb = true;
		}
		if(alb && art)
		{
			return;
		}
		File[] files = folder.listFiles(filter);
		String lastart = "";
		String lastalbum = "";
		try{
			for(File f: files)
			{
				InputStream input = new FileInputStream(f);
				BufferedInputStream binput = new BufferedInputStream(input);
				ContentHandler handler = new DefaultHandler();
				Metadata metadata = new Metadata();
				Parser parser = null;
				if(f.getName().toLowerCase().endsWith("mp3")){
					parser = new Mp3Parser();
				}
				else if(f.getName().toLowerCase().endsWith("flac")){
					parser = new FlacParser();
				}
				else if(f.getName().toLowerCase().endsWith("wav")){
					parser = new AudioParser();
				}
				else{
					parser = new MP4Parser(); //works with m4a
				}
				ParseContext parseCtx = new ParseContext();
				parser.parse(binput, handler, metadata, parseCtx);
				input.close();

				/**
		        // List all metadata
		        String[] metadataNames = metadata.names();

		        for(String name : metadataNames){
		        System.out.println(name + ": " + metadata.get(name));
		        }
				 */
				String artist; 
				artist = metadata.get("xmpDM:artist");
				if(artist == null)
					artist = metadata.get("albumartist");
				String artarg = "artist=" + artist;
				String track = "track=" + metadata.get("dc:title");
				//System.out.println(track);
				if(track.equals("track=null")){
					track = "track=" + trackFromFileName(f.getName());
				}
				//System.out.println(artarg);
				try{
					if(artist!=null && !art && !artist.equals(lastart)) //if we couldn't find art for the artist last time, we won't be able to this time either. Check if next track is of a different artist instead!
					{
						lastart = artist;
						art = getArtistArt(artist, artarg, track, folder, f);
					}
					if(!alb)
					{
						String album = "album=" + metadata.get("xmpDM:album");
						alb = getAlbumArt(artist, artarg, folder, f, album, lastalbum, track);
						lastalbum = album;
					}
				}
				catch(Exception e){lastart = ""; lastalbum = ""; addProblematic(folder); 
				//e.printStackTrace();
				}
				if(thorough && (!alb || !art)) //if art could not be found with data from the first track, try other tracks in the folder.. useful when tags are wonky
					continue;
				break;
			}
		}
		catch(Exception e){
			//e.printStackTrace();
		}
	}

	/**
	 * Tries to get track name from name of file.
	 * Not all tracks are aptly named, but this should give us a few more matches.
	 * @param name
	 * @return
	 */
	private String trackFromFileName(String name) {
		name = name.replaceFirst("(*[0-9]+)*\\.* *", ""); //try to remove track number indicators from file names.
		name = name.replaceFirst("(\\.mp3)|(\\.flac)|(\\.wav)|(\\.m4a)", "");
		System.out.println(name);
		return name;
	}

	/**
	 * Downloads and saves album art if album art is found on last.fm. First checks if art can be found by getting the track's info(based on name and artist). 
	 * If that fails, tries to download art by using the tagged album name.
	 * @param artist
	 * @param artarg
	 * @param autocorrect
	 * @param folder
	 * @param f
	 * @param album
	 * @param lastalbum
	 * @param track
	 * @return
	 */
	private boolean getAlbumArt(String artist, String artarg, File folder, File f, String album, String lastalbum, String track) throws Exception
	{
		boolean alb = false;
		File pic = new File(f.getParent() + "/cover.png");
		Image res = null;
		try{
			res = getImage("track.getInfo",track, artarg, autocorrect);
		}
		catch(Exception e){}
		if(res!=null){
			ImageIO.write((RenderedImage) res, "png", pic);
			albumLock.lock();
			albumsDownloaded++;
			albumLock.unlock();
			alb = true;
		}
		else
		{
			if(!album.equals(lastalbum))
			{
				try{
				res = getImage("album.getInfo",artarg, album, autocorrect);
				}
				catch(Exception e){}
				if(res!=null){
					ImageIO.write((RenderedImage) res, "png", pic);
					albumLock.lock();
					albumsDownloaded++;
					albumLock.unlock();
					alb = true;
				}
			}
			if(!alb){
				addProblematic(folder);
			}
		}
		return alb;

	}

	/**
	 * Downloads and saves artist art if art exists on last.fm. Tries to avoid downloading the same image twice by saving it locally and checking if image is saved before downloading.
	 * @param artist
	 * @param artarg
	 * @param track
	 * @param folder
	 * @param f
	 * @return
	 */
	private boolean getArtistArt(String artist, String artarg, String track, File folder, File f) throws Exception
	{
		boolean saved = false;
		boolean art = false;
		if(artistImages.containsKey(artist)) 
		{
			System.out.println("SAVED BANDWIDTH !!");
			File pic = new File(f.getParent() + "/artist.png");
			ImageIO.write((RenderedImage) artistImages.get(artist), "png", pic);
			saved = true;
			art = true;
		}
		if(!saved){
			File pic = new File(f.getParent() + "/artist.png");
			Image res = null;
			try{
				res = getImage("artist.getInfo",artarg, autocorrect);
			}
			catch(Exception e){ 
			}
			try{
			if(res == null && (artarg.matches(".+(feat|ft)\\.*.+") || artarg.matches(".+\\(\\?+\\)") || artarg.matches(".+&.*"))) // if we can't find artist, try to scrub data..
			{
				artarg = artarg.replaceFirst(" *(feat|ft)\\.*.+", "");
				artarg = artarg.replaceFirst(" *\\(\\?+\\)", "");
				artarg = artarg.replaceFirst(" *&.*", "");
				res = getImage("artist.getInfo",artarg, autocorrect);
			}
			}
			catch(Exception e){}
			if(res!=null){
				ImageIO.write((RenderedImage) res, "png", pic);
				artistListLock.lock();
				artistImages.put(artist, res); // this should save quite a lot of bandwidth(and time) when multiple albums of the same artist exist.
				artistListLock.unlock();
				artistLock.lock();
				artistsDownloaded++;
				artistLock.unlock();
				art = true;
			}
			if(!art)
			{
				addProblematic(folder);
			}
		}
		return art;
	}

	/**
	 * add folder to problematicFolders list.
	 * @param folder
	 */
	private synchronized void addProblematic(File folder)
	{
		Integer t = problematicFolders.get(folder.getAbsolutePath());
		if(t == null)
			t = 1;
		else
			t++;
		problematicFolders.put(folder.getAbsolutePath(),t);
	}

	/**
	 * Downloads image from last.fm API.
	 * @param method
	 * @param params Parameters needed for the GET request.
	 * @return Image
	 * @throws Exception
	 */
	public static Image getImage(String method, String... params) throws Exception {
		try{
		//StringBuilder result = new StringBuilder();
		StringBuilder urlToRead = new StringBuilder();
		urlToRead.append(host);
		urlToRead.append("?");
		urlToRead.append("method=" + method);
		urlToRead.append(API_STRING);
		for(String param : params)
		{
			urlToRead.append("&");
			urlToRead.append(param);
		}
		//System.out.println(urlToRead);		
		URL url = new URL(urlToRead.toString().replaceAll(" ", "%20"));
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
		String line;
		String imgUrl = "";
		while ((line = rd.readLine()) != null) { // grab largest picture available
			if(line.startsWith("<image size=\"small"))
				imgUrl = line.substring(20, line.length()-8);
			else if(line.startsWith("<image size=\"medium"))
				imgUrl = line.substring(21, line.length()-8);
			else if(line.startsWith("<image size=\"large"))
				imgUrl = line.substring(20, line.length()-8);
			else if(line.startsWith("<image size=\"extralarge"))
				imgUrl = line.substring(25, line.length()-8);
			else if(line.startsWith("<image size=\"mega")){
				imgUrl = line.substring(19, line.length()-8);
				break;
			}
			//result.append(line);
		}
		rd.close();
		//System.out.println(result);
		Image image = null;
		try{
			image = ImageIO.read(new URL(imgUrl));  
		}
		catch(Exception e){}
		return image;
	}catch(Exception e){//e.printStackTrace();
	return null;
	}
	}
		
}
