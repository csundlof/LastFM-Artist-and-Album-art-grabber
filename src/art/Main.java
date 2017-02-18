package art;

import java.awt.Image;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Stack;

import javax.imageio.ImageIO;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.mp3.Mp3Parser;
import org.apache.tika.parser.mp4.MP4Parser;
import org.gagravarr.tika.FlacParser;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.DefaultHandler;
public class Main extends Thread {

	private static final String host = "http://ws.audioscrobbler.com/2.0/", API_KEY = "1467cbdbeb8e49fab49312b7e236944a";
	private static Stack<File> folders = new Stack<File>();
	private final static int NUMWORKERS = Runtime.getRuntime().availableProcessors();
	private final Lock _mutex = new ReentrantLock(true);
	private final Lock artistLock = new ReentrantLock(true), albumLock = new ReentrantLock(true), artistListLock = new ReentrantLock(true);
	private static int artistsDownloaded = 0;
	private static int albumsDownloaded = 0;
	private HashMap<String, Image> artistImages = new HashMap<String, Image>(); //store already downloaded images 

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
		if (lowercaseName.matches("(.+mp3)|(.+flac)|(.+m4a)")) {
			return true;
		} else {
			return false;
		}
	};
	
	public static void main(String[] args) {
		String path = "";
		if(args.length == 0){
			Path currentRelativePath = Paths.get("");
			String s = currentRelativePath.toAbsolutePath().toString();
			path = s;
		}
		else{
			path = args[0];
			for(int i = 1; i < args.length; ++i)
				path = path + " " + args[i];
		}
		System.out.println("Root folder: " + path);
		System.out.println("Program will run using " + NUMWORKERS + " threads");
		File[] directories = new File(path).listFiles(File::isDirectory);
		for(File f : directories)
		{
			folders.push(f);
		}
		Thread threads[] = new Thread[NUMWORKERS];
		for(int i = 0; i < NUMWORKERS; ++i){
			threads[i] = new Thread(new Main());
			threads[i].start();
		}
		for(int i = 0; i < threads.length; ++i)
			try {
				threads[i].join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		System.out.println("Downloaded:\n" + artistsDownloaded + " artist pictures.\n" + albumsDownloaded + " album covers.");
	}

	/**
	 * Worker method.
	 */
	public void run() {
		Long id = this.getId();
		System.out.println(id + " started.");
		while(folders.size()>0){
			_mutex.lock();
			if(folders.size()==0){
				_mutex.unlock();
				break;
			}
			File folder = folders.pop(); // grab work from bag of tasks
			_mutex.unlock();
			downloadImages(folder, id);
			System.out.println(folders.size() + " folders remaining.");
		}
		System.out.println(id + " finished.");
	}

	/**
	 * Searches deep until no more folders are found. Downloads images belonging to any songs regardless of their level.
	 * If there are too many folders in the folder, they are added to the bag of task and the recursion is interrupted.
	 * @param folder
	 * @param id
	 */
	private void downloadImages(File folder, long id) {
		//System.out.println("hey, I'm looking at " + folder.getName() + ". t. " + id);
		File[] directories = folder.listFiles(File::isDirectory);
		if(directories.length > NUMWORKERS*2) // this folder has many subfolders, let's split the work up.
		{
			_mutex.lock();
			for(File f : directories)
			{
				folders.push(f);
			}
			_mutex.unlock();
			System.out.println("Added " + directories.length + " folders to bag of tasks.");
		}
		else{
			for(File f : directories)
			{
				downloadImages(f, id);
			}
		}
		boolean art = false, alb = false;
		for(File f : folder.listFiles(filter2))
		{
			if(f.getName().startsWith("artist")){
				art = true;
			}
			else if(f.getName().startsWith("cover"))
				alb = true;
		}
		File[] files = folder.listFiles(filter);
		try{
			for(File f: files)
			{
				InputStream input = new FileInputStream(f);
				BufferedInputStream binput = new BufferedInputStream(input);
				ContentHandler handler = new DefaultHandler();
				Metadata metadata = new Metadata();
				Parser parser = null;
				if(f.getName().endsWith("mp3")){
					parser = new Mp3Parser();
				}
				else if(f.getName().endsWith("flac")){
					parser = new FlacParser();
				}
				else{
					parser = new MP4Parser(); //works with m4a
				}
				ParseContext parseCtx = new ParseContext();
				parser.parse(binput, handler, metadata, parseCtx);
				input.close();

				String autocorrect = "autocorrect=1";
				String artist = metadata.get("xmpDM:artist");
				String artarg = "artist=" + artist;
				String albarg = "album=" + metadata.get("xmpDM:album");
				if(!art)
				{
					boolean saved = false;
					if(artistImages.containsKey(artist)) 
					{
						System.out.println("SAVED BANDWIDTH !!");
						File pic = new File(f.getParent() + "/artist.png");
						ImageIO.write((RenderedImage) artistImages.get(artist), "png", pic);
						saved = true;
					}
					if(!saved){
						File pic = new File(f.getParent() + "/artist.png");
						Image res = getImage("artist.getInfo",artarg, autocorrect);
						if(res!=null){
							ImageIO.write((RenderedImage) res, "png", pic);
							artistListLock.lock();
							artistImages.put(artist, res); // this should save quite a lot of bandwidth(and time) when multiple albums of the same artist exist.
							artistListLock.unlock();
							artistLock.lock();
							artistsDownloaded++;
							artistLock.unlock();
						}
					}
				}
				if(!alb)
				{
					File pic = new File(f.getParent() + "/cover.png");
					Image res = getImage("album.getInfo",artarg, albarg, autocorrect);
					if(res!=null){
						ImageIO.write((RenderedImage) res, "png", pic);
						albumLock.lock();
						albumsDownloaded++;
						albumLock.unlock();
					}
				}
				break;
			}
		}
		catch(Exception e){
			//e.printStackTrace();
		}
	}

	/**
	 * Downloads image from last.fm API.
	 * @param method
	 * @param params eventual parameters needed for the GET request.
	 * @return Image
	 * @throws Exception
	 */
	public static Image getImage(String method, String... params) throws Exception {
		//StringBuilder result = new StringBuilder();
		StringBuilder urlToRead = new StringBuilder();
		urlToRead.append(host);
		urlToRead.append("?");
		urlToRead.append("method=" + method);
		urlToRead.append("&api_key=" + API_KEY);
		for(String param : params)
		{
			urlToRead.append("&");
			urlToRead.append(param);
		}
		URL url = new URL(urlToRead.toString());
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
		String line;
		while ((line = rd.readLine()) != null) {
			if(line.startsWith("<image size=\"mega")){
				line = line.substring(19, line.length()-8);
				break;
			}
			//result.append(line);
		}
		rd.close();
		//System.out.println(result);
		Image image = null;
		try{
			image = ImageIO.read(new URL(line));  
		}
		catch(Exception e){}
		return image;
	}
}
