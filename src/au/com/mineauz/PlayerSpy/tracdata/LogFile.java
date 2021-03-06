package au.com.mineauz.PlayerSpy.tracdata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.io.*;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import au.com.mineauz.PlayerSpy.LogUtil;
import au.com.mineauz.PlayerSpy.RecordList;
import au.com.mineauz.PlayerSpy.SpyPlugin;
import au.com.mineauz.PlayerSpy.LogTasks.*;
import au.com.mineauz.PlayerSpy.Records.*;
import au.com.mineauz.PlayerSpy.Utilities.ACIDRandomAccessFile;
import au.com.mineauz.PlayerSpy.Utilities.BoundingBox;
import au.com.mineauz.PlayerSpy.Utilities.CubicChunk;
import au.com.mineauz.PlayerSpy.Utilities.Util;
import au.com.mineauz.PlayerSpy.debugging.CrashReporter;
import au.com.mineauz.PlayerSpy.debugging.Debug;
import au.com.mineauz.PlayerSpy.debugging.Profiler;
import au.com.mineauz.PlayerSpy.monitoring.CrossReferenceIndex;
import au.com.mineauz.PlayerSpy.structurefile.Index;
import au.com.mineauz.PlayerSpy.structurefile.SpaceLocator;
import au.com.mineauz.PlayerSpy.structurefile.StructuredFile;
import au.com.mineauz.PlayerSpy.tracdata.SessionIndex.SessionData;
import au.com.mineauz.PlayerSpy.wrappers.nbt.NBTCompressedStreamTools;
import au.com.mineauz.PlayerSpy.wrappers.nbt.NBTTagCompound;

public class LogFile extends StructuredFile
{
	public LogFile()
	{
		mReferenceCount = 1;
		mReferenceLock = new ReentrantLock(true);
	}

	/**
	 * Increases the reference count.
	 * Be sure to call close() when done
	 */
	public void addReference()
	{
		mReferenceLock.lock();
		
		if(mTimeoutId != -1)
		{
			Bukkit.getScheduler().cancelTask(mTimeoutId);
			mTimeoutId = -1;
		}
		mReferenceCount++;
		mReferenceLock.unlock();
		
	}
	
	public boolean isLoaded()
	{
		return mIsLoaded && !mIsClosing;
	}
	public boolean isCorrupted()
	{
		return mIsCorrupt;
	}
	public boolean isTimingOut()
	{
		return mTimeoutId != -1;
	}
	public boolean requiresOwnerTags()
	{
		return mHeader.RequiresOwnerTags;
	}
	 
	public long getStartDate()
	{
		lockRead();
		
		long result = Long.MAX_VALUE;
		if(mSessionIndex.getCount() > 0)
			result = mSessionIndex.get(0).StartTimestamp;
		
		unlockRead();
		
		return result;
	}
	
	public long getEndDate()
	{
		lockRead();
		
		long result = 0;
		if(mSessionIndex.getCount() > 0)
			result = mSessionIndex.get(mSessionIndex.getCount()-1).EndTimestamp;
		
		unlockRead();
		
		return result;
	}
	
	void pullDataExposed( long location ) throws IOException
	{
		super.pullData(location);
	}
	
	/**
	 * Creates a blank log file ready to accept sessions.
	 * It will be open when returned so remember to call close() when you're done
	 * @param playerName The name of the player this is a log of
	 * @param filename The filename to create the log at
	 * @return An instance with an open log or null if unable to create it
	 */
	public static LogFile create(String playerName, String filename)
	{
		ACIDRandomAccessFile file = null;
		try 
		{
			file = new ACIDRandomAccessFile(filename, "rw");
		}
		catch (FileNotFoundException e) 
		{
			return null;
		}
		
		
		// Write the file header
		FileHeader header = new FileHeader();
		
		header.PlayerName = playerName;
		header.HolesIndexLocation = header.getSize();
		header.HolesIndexSize = 0;
		header.HolesIndexCount = 0;
		header.HolesIndexPadding = 0;
		
		header.IndexLocation = header.getSize();
		header.IndexSize = 0;
		header.SessionCount = 0;
		
		header.OwnerMapCount = 0;
		header.OwnerMapSize = 0;
		header.OwnerMapLocation = header.getSize();
		
		header.RollbackIndexCount = 0;
		header.RollbackIndexSize = 0;
		header.RollbackIndexLocation = header.getSize();
		
		header.ChunkIndexCount = 0;
		header.ChunkIndexSize = 0;
		header.ChunkIndexLocation = header.getSize();
		
		if (playerName.startsWith(LogFileRegistry.cGlobalFilePrefix))
			header.RequiresOwnerTags = true;
		else
			header.RequiresOwnerTags = false;
		
		
		try
		{
			// Clean it first
			file.setLength(0);
			file.beginTransaction();
			
			header.write(file);
			
			file.commit();
		}
		catch(IOException e)
		{
			LogUtil.severe("Failed to create log file for " + playerName);
			
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Logfile creation");
			reporter.addVariable("LogFile", playerName);
			
			Debug.logCrash(reporter);
			
			file.rollback();
			
			return null;
		}
		
		// initialize the logfile instance
		
		LogFile log = new LogFile();
		log.mPlayerName = playerName;
		
		log.mSpaceLocator = new SpaceLocator(log);
		log.mHoleIndex = new HoleIndex(log, header, file, log.mSpaceLocator);
		log.mSpaceLocator.setHoleIndex(log.mHoleIndex);
		
		log.mSessionIndex = new SessionIndex(log, header, file, log.mSpaceLocator);
		log.mOwnerTagIndex = new OwnerTagIndex(log, header, file, log.mSpaceLocator);
		log.mRollbackIndex = new RollbackIndex(log, header, file, log.mSpaceLocator);
		log.mChunkIndex = new ChunkIndex(log, header, file, log.mSpaceLocator);
		
		log.load(file, new File(filename), new Index[] {log.mHoleIndex, log.mSessionIndex, log.mOwnerTagIndex, log.mRollbackIndex, log.mChunkIndex});
		
		log.mIsLoaded = true;
		log.mFile = file;
		log.mHeader = header;
		log.mReferenceCount = 1;
		
		
		Debug.fine("Created a log file for '" + playerName + "'.");
		
		return log;
	}
	
	/**
	 * Reads the header of a log file and returns it.
	 * @param filename The filename of the log file
	 */
	public static FileHeader scrapeHeader(String filename)
	{
		RandomAccessFile file = null;
		try
		{
			file = new RandomAccessFile(new File(filename), "r");
			FileHeader header = new FileHeader();
			header.read(file);
			
			file.close();
			
			return header;
		} 
		catch (Exception e) 
		{
			Debug.logException(e);
		}
		
		return null;
	}
	/**
	 * Attempts to load the log file
	 * @param filename The filename of the log file
	 * @return If the log file loads correctly, this will return true.
	 */
	public boolean load(String filename)
	{
		Debug.loggedAssert(!mIsLoaded);
		
		boolean ok = false;
		lockWrite();
		ACIDRandomAccessFile file = null;
		mIsCorrupt = false;
		try
		{
			Debug.info("Loading '" + filename + "'...");
			file = new ACIDRandomAccessFile(filename, "rw");
			
			// Read the file header
			FileHeader header = new FileHeader();
			header.read(file);
			
			mHeader = header;
			
			// Initialize the indexes
			mSpaceLocator = new SpaceLocator(this);
			mHoleIndex = new HoleIndex(this, mHeader, file, mSpaceLocator);
			mSpaceLocator.setHoleIndex(mHoleIndex);
			
			mSessionIndex = new SessionIndex(this, mHeader, file, mSpaceLocator);
			mOwnerTagIndex = new OwnerTagIndex(this, mHeader, file, mSpaceLocator);
			mRollbackIndex = new RollbackIndex(this, mHeader, file, mSpaceLocator);
			mChunkIndex = new ChunkIndex(this, mHeader, file, mSpaceLocator);
			
			load(file, new File(filename), new Index[] {mHoleIndex, mSessionIndex, mOwnerTagIndex, mRollbackIndex, mChunkIndex});
			
			// Read the indices
			mHoleIndex.read();
			
			if(header.VersionMajor >= 2)
				mOwnerTagIndex.read();

			// So that active sessions are mapped correctly
			mSessionIndex.read();
			
			if(header.VersionMajor >= 3)
				mRollbackIndex.read();
			
			if(header.VersionMajor >= 4)
				mChunkIndex.read();

			readTags();
			
			mPlayerName = header.PlayerName;
			
			mFile = file;
			
			Debug.info("Load Succeeded:");
			Debug.info(" Player: " + mPlayerName);
			Debug.fine(" Sessions found: " + mHeader.SessionCount);
			Debug.fine(" Holes found: " + mHeader.HolesIndexCount);
			if(mSessionIndex.getCount() > 0)
			{
				Debug.fine(" Earliest Date: " + Util.dateToString(mSessionIndex.get(0).StartTimestamp));
				Debug.fine(" Latest Date: " + Util.dateToString(mSessionIndex.get(mSessionIndex.getCount()-1).EndTimestamp));
			}
			
			mIsLoaded = true;
			ok = true;
		}
		catch(Throwable e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Loading log file");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("Path", filename);
			
			Debug.logCrash(reporter);

			try
			{
				if(file != null)
					file.close();
			}
			catch(IOException ex)
			{
				Debug.logException(ex);
			}
			ok = false;
			mIsCorrupt = true;
		}
		
		unlockWrite();
		
		return ok;
	}

	public Future<Boolean> loadAsync(String filename)
	{
		Debug.loggedAssert(!mIsLoaded);
		
		return SpyPlugin.getExecutor().submit(new LogLoadTask(this, filename));
	}
	
	/**
	 * Decreases the reference count and closes the log file if needed
	 */
	public void close(boolean noTimeout)
	{
		Debug.loggedAssert(mIsLoaded); // FIXME: Assertion thrown TASK: Assertion in LogFile.close
		Debug.loggedAssert(!mIsClosing);
		Debug.loggedAssert(mTimeoutId == -1);
		
		mReferenceLock.lock();
		
		mReferenceCount--;
		
		if(mReferenceCount <= 0)
		{
			mReferenceLock.unlock();
			
			if(noTimeout || sNoTimeoutOverride)
			{
				mIsClosing = true;
				try 
				{
					// Add the close task and wait for it
					//Future<?> future = mAsyncService.submit(new CloseTask());
					Future<?> future = SpyPlugin.getExecutor().submit(new CloseTask());
					future.get();
				} 
				catch (InterruptedException e1) 
				{
					Debug.logException(e1);
				} 
				catch (ExecutionException e) 
				{
					Debug.logException(e);
				}
			}
			else
			{
				mTimeoutId = Bukkit.getScheduler().scheduleSyncDelayedTask(SpyPlugin.getInstance(), new Runnable() 
				{
					@Override
					public void run() 
					{
						mIsClosing = true;
						mTimeoutId = -1;
						Debug.finer("Submitting the close task");
						//mAsyncService.submit(new CloseTask());
						SpyPlugin.getExecutor().submit(new CloseTask());
					}
				}, SpyPlugin.getSettings().logTimeout / 50L);
			}
		}
		else
		{
			mReferenceLock.unlock();
			
		}
	}
	/**
	 * Decreases the reference count and closes the log file if needed
	 * This method is Asynchronous
	 */
	public void closeAsync(boolean noTimeout)
	{
		Debug.loggedAssert(mIsLoaded);
		Debug.loggedAssert(!mIsClosing);
		Debug.loggedAssert(mTimeoutId == -1);
		
		mReferenceLock.lock();
		
		mReferenceCount--;
		
		if(mReferenceCount <= 0)
		{
			mReferenceLock.unlock();
			
			if(noTimeout || sNoTimeoutOverride)
			{
				mIsClosing = true;
				Debug.finer("Submitting the close task");
				//mAsyncService.submit(new CloseTask());
				SpyPlugin.getExecutor().submit(new CloseTask());
			}
			else
			{
				mTimeoutId = Bukkit.getScheduler().scheduleSyncDelayedTask(SpyPlugin.getInstance(), new Runnable() 
				{
					@Override
					public void run() 
					{
						mIsClosing = true;
						mTimeoutId = -1;
						Debug.finer("Submitting the close task");
						//mAsyncService.submit(new CloseTask());
						SpyPlugin.getExecutor().submit(new CloseTask());
					}
				}, SpyPlugin.getSettings().logTimeout / 50L);
			}
		}
		else
		{
			mReferenceLock.unlock();
			
		}
	}
	
	/**
	 * Gets all the available chunks of data
	 */
	public List<SessionEntry> getSessions()
	{
		return mSessionIndex.getEntries();
	}
	public SessionEntry getSessionById(int id)
	{
		return mSessionIndex.getSessionFromId(id);
	}
	public String getOwnerTag(SessionEntry session)
	{
		if(session.OwnerTagId == -1)
			return null;
		
		lockRead();
		
		OwnerMapEntry entry = mOwnerTagIndex.getOwnerTagById(session.OwnerTagId);
		
		String result = null;
		if(entry != null)
			result = entry.Owner;
				
		unlockRead();
		
		return result;
	}
	public String getOwnerTag(int sessionIndex)
	{
		return getOwnerTag(mSessionIndex.get(sessionIndex));
	}
	
	public Set<CubicChunk> getPresentChunks(int sessionId)
	{
		SessionEntry session = mSessionIndex.getSessionFromId(sessionId);
		
		if(session == null)
			return null;
		
		return getPresentChunks(session);
	}
	public Set<CubicChunk> getPresentChunks(SessionEntry session)
	{
		if(session.ChunkListId == -1)
			return null;
		
		lockRead();
		
		try
		{
			return mChunkIndex.getChunks(session.ChunkListId);
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			unlockRead();
		}
		
		return null;
	}
	
	/**
	 * Loads records between the specified dates that have the specified ownertag and returns them
	 * @param startDate The earliest date to retrieve records for
	 * @param endDate The latest date to retrieve records for
	 * @param owner The owner of the records
	 * @return The list of records whose timestamps fall between the start and end dates 
	 */
	public RecordList loadRecords(long startDate, long endDate, String owner)
	{
		Debug.loggedAssert(mIsLoaded);
		Debug.loggedAssert(mHeader.VersionMajor >= 2, "Owner tags are only suppored in version 2 and above");
		if(!mHeader.RequiresOwnerTags)
			throw new IllegalStateException("Owner tags are not enabled in this log");
		
		Profiler.beginTimingSection("loadRecords");
		RecordList allRecords = loadRecordChunks(startDate,endDate,owner);
		
		if (allRecords == null)
			return null;
		
		int trimStart = 0;
		int trimEnd = 0;
		
		// Now trim the records to the time period specified
		for(int i = 0; i < allRecords.size(); i++)
		{
			// Find start Trim
			if(allRecords.get(i).getTimestamp() < startDate)
				trimStart = i;
			// Find end trim
			else if(allRecords.get(i).getTimestamp() >= endDate)
			{
				trimEnd = i;
				break;
			}
		}
		
		allRecords.splitRecords(trimEnd, true);
		allRecords.splitRecords(trimStart, false);

		Debug.fine("  " + allRecords.size() + " returned records");
		Profiler.endTimingSection();
		
		return allRecords;
	}
	/**
	 * Loads records between the specified dates that have the specified ownertag and returns them 
	 * This method is Asynchronous
	 * @param startDate The earliest date to retrieve records for
	 * @param endDate The latest date to retrieve records for
	 * @param owner The owner of the records
	 * @return A Future which gives access to the list of records whose timestamps fall between the start and end dates 
	 */
	public Future<RecordList> loadRecordsAsync(long startDate, long endDate, String owner)
	{
		Debug.finer("Submitting loadRecords async task");
		return SpyPlugin.getExecutor().submit(new LoadRecordsAsyncTask(this, startDate, endDate, false, owner));
	}
	/**
	 * Loads records between the specified dates and returns them
	 * @param startDate The earliest date to retrieve records for
	 * @param endDate The latest date to retrieve records for
	 * @return The list of records whose timestamps fall between the start and end dates 
	 */
	public RecordList loadRecords(long startDate, long endDate)
	{
		Debug.loggedAssert(mIsLoaded);
		
		Profiler.beginTimingSection("loadRecords");
		RecordList allRecords = loadRecordChunks(startDate, endDate);
		
		if(allRecords == null)
			return null;
		
		int trimStart = 0;
		int trimEnd = 0;
		
		// Now trim the records to the time period specified
		for(int i = 0; i < allRecords.size(); i++)
		{
			// Find start Trim
			if(allRecords.get(i).getTimestamp() < startDate)
				trimStart = i;
			// Find end trim
			else if(allRecords.get(i).getTimestamp() >= endDate)
			{
				trimEnd = i;
				break;
			}
		}
		
		allRecords.splitRecords(trimEnd, true);
		allRecords.splitRecords(trimStart, false);

		Debug.fine("  " + allRecords.size() + " returned records");
		Profiler.endTimingSection();
		
		return allRecords;
	}
	/**
	 * Loads records between the specified dates and returns them
	 * This method is Asynchronous
	 * @param startDate The earliest date to retrieve records for
	 * @param endDate The latest date to retrieve records for
	 * @return A Future which gives access to the list of records whose timestamps fall between the start and end dates 
	 */
	public Future<RecordList> loadRecordsAsync(long startDate, long endDate)
	{
		Debug.finer("Submitting loadRecords async task");
		return SpyPlugin.getExecutor().submit(new LoadRecordsAsyncTask(this, startDate, endDate, false));
	}
	
	public long getNextAvailableDateAfter(long date)
	{
		long result = 0;
		lockRead();
		
		for(SessionEntry entry : mSessionIndex)
		{
			if(entry.StartTimestamp > date && entry.OwnerTagId == -1)
			{
				result = entry.StartTimestamp;
				break;
			}
		}
		unlockRead();
		
		return result;
	}
	public long getNextAvailableDateBefore(long date)
	{
		long result = 0;
		lockRead();
		
		
		for(int i = mSessionIndex.getCount()-1; i >=0 ; --i)
		{
			SessionEntry entry = mSessionIndex.get(i);
			if(entry.EndTimestamp > date && entry.OwnerTagId == -1)
			{
				result = entry.EndTimestamp;
				break;
			}
		}
		unlockRead();
		
		return result;
	}
	/**
	 * Loads entire chunks of records that have have records within the start and end date that are for the specified ownertag
	 * This is more efficient than the loadRecords() method but in most cases will return records before the start date and beyond the end date. 
	 * @param startDate The earliest date you would like to retrieve records for. 
	 * @param endDate The latest date you would like to retrieve records for.
	 * @param owner The owner of the records
	 * @return The list of records
	 */
	public RecordList loadRecordChunks(long startDate, long endDate, String owner)
	{
		Debug.loggedAssert(mIsLoaded);
		Debug.loggedAssert(mHeader.VersionMajor >= 2, "Owner tags are only suppored in version 2 and above");
		if(!mHeader.RequiresOwnerTags)
			throw new IllegalStateException("Owner tags are not enabled in this log");
		
		Profiler.beginTimingSection("loadChunks");
		ArrayList<SessionEntry> relevantEntries = new ArrayList<SessionEntry>();
		
		Debug.fine("Loading records from " + Util.dateToString(startDate) + " to " + Util.dateToString(endDate));
		
		// We will hold the write lock because accessing the file concurrently through the same object with have issues i think.
		lockWrite();
		
				
		// Find the relevant sessions
		for(SessionEntry entry : mSessionIndex)
		{
			if((startDate >= entry.StartTimestamp && startDate <= entry.EndTimestamp) ||
				(endDate >= entry.StartTimestamp && endDate <= entry.EndTimestamp) ||
				(entry.StartTimestamp >= startDate && entry.StartTimestamp < endDate) ||
				(entry.EndTimestamp > startDate && entry.EndTimestamp < endDate))
			{
				if(owner.equalsIgnoreCase(getOwnerTag(entry)))
				{
					relevantEntries.add(entry);
					break;
				}
					
			}
			
		}
		Debug.finer("  " + relevantEntries.size() + " Matching Sessions");
		// Now load up the records
		RecordList allRecords = new RecordList();
		for(SessionEntry session : relevantEntries)
		{
			RecordList list = loadSession(session);
			if(list != null)
				allRecords.addAll(list);
		}
		
		unlockWrite();
		Profiler.endTimingSection();
		
		Debug.finer("  " + allRecords.size() + " loaded records");

		return allRecords;
	}
	/**
	 * Loads entire chunks of records that have have records within the start and end date that are for the specified ownertag
	 * This is more efficient than the loadRecords() method but in most cases will return records before the start date and beyond the end date.
	 * This method is Asynchronous. 
	 * @param startDate The earliest date you would like to retrieve records for. 
	 * @param endDate The latest date you would like to retrieve records for.
	 * @param owner The owner of the records
	 * @return A future which gives you access to the records when they have been retrieved
	 */
	public Future<RecordList> loadRecordChunksAsync(long startDate, long endDate, String owner)
	{
		Debug.finer("Submitting loadRecordChunks async task");
		return SpyPlugin.getExecutor().submit(new LoadRecordsAsyncTask(this, startDate, endDate, true, owner));
	}
	/**
	 * Loads entire chunks of records that have have records within the start and end date
	 * This is more efficient than the loadRecords() method but in most cases will return records before the start date and beyond the end date. 
	 * @param startDate The earliest date you would like to retrieve records for. 
	 * @param endDate The latest date you would like to retrieve records for.
	 * @return The list of records
	 */
	public RecordList loadRecordChunks(long startDate, long endDate)
	{
		Debug.loggedAssert(mIsLoaded);
		Profiler.beginTimingSection("loadChunks");
		Debug.fine("Loading chunks from " + Util.dateToString(startDate) + " to " + Util.dateToString(endDate));
		ArrayList<SessionEntry> relevantEntries = new ArrayList<SessionEntry>();
		
		// We will hold the write lock because accessing the file concurrently through the same object with have issues i think.
		lockWrite();
					
		
		// Find the relevant sessions
		for(SessionEntry entry : mSessionIndex)
		{
			if((startDate >= entry.StartTimestamp && startDate <= entry.EndTimestamp) ||
			   (endDate >= entry.StartTimestamp && endDate <= entry.EndTimestamp) ||
			   (entry.StartTimestamp >= startDate && entry.StartTimestamp < endDate) ||
			   (entry.EndTimestamp > startDate && entry.EndTimestamp < endDate))
			{
				if(entry.OwnerTagId == -1)
					relevantEntries.add(entry);
			}
		}
		Debug.finer("  " + relevantEntries.size() + " Matching Sessions");
		// Now load up the records
		RecordList allRecords = new RecordList();
		for(SessionEntry session : relevantEntries)
		{
			RecordList list = loadSession(session);
			if(list != null)
				allRecords.addAll(list);
		}
		Debug.fine("  " + allRecords.size() + " loaded records");
		
		unlockWrite();
		Profiler.endTimingSection();
		
		// No need to trim it
		return allRecords;
	}
	/**
	 * Loads entire chunks of records that have have records within the start and end date
	 * This is more efficient than the loadRecords() method but in most cases will return records before the start date and beyond the end date.
	 * This method is Asynchronous. 
	 * @param startDate The earliest date you would like to retrieve records for. 
	 * @param endDate The latest date you would like to retrieve records for.
	 * @return A future which gives you access to the records when they have been retrieved
	 */
	public Future<RecordList> loadRecordChunksAsync(long startDate, long endDate)
	{
		Debug.finer("Submitting loadRecordChunks async task");
		return SpyPlugin.getExecutor().submit(new LoadRecordsAsyncTask(this, startDate, endDate, true));
	}
	public RecordList loadSession(SessionEntry session)
	{
		Debug.loggedAssert(mIsLoaded);
		
		Profiler.beginTimingSection("loadSession");
		Debug.startSection();
		
		// We will hold the write lock because accessing the file concurrently through the same object with have issues i think.
		lockWrite();
		
		try
		{
			SessionData data = mSessionIndex.getDataFor(session);
			return data.read();
		}
		catch(RecordFormatException e)
		{
			// Try to recover from the error
			RecordList records = new RecordList();
			records.addAll(e.getSucceededRecords());
			
			try
			{
				// Load the rollback state info in
				short[] indices = mRollbackIndex.getRolledBackRecords(session);
				for(int i = 0; i < indices.length; ++i)
				{
					if(indices[i] < 0 || indices[i] >= records.size())
						continue;
					
					Record record = records.get(indices[i]);
					if(record instanceof IRollbackable)
					{
						IRollbackable r = (IRollbackable)record;
						r.setRollbackState(true);
					}
				}
			}
			catch(IOException ex)
			{
				Debug.logExceptionDebugOnly(ex);
			}
			
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Loading session");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("Session", session.Id);
			reporter.addVariable("Count", session.RecordCount);
			reporter.addVariable("Is Compressed?", session.Compressed);
			reporter.addVariable("Success Count", records.size());
			reporter.addVariable("Source Type", e.getSourceType());
			reporter.addVariables(Debug.getVariables());
			try
			{
				reporter.addVariable("PTR", mFile.getFilePointer());
			}
			catch ( IOException e1 )
			{
				e1.printStackTrace();
			}
			
			Debug.logCrashDebugOnly(reporter);
			
			mKnownBadSessions.add(session.Id);
			
			return records;
		}
		catch(Throwable e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Loading session");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("Session", session.Id);
			reporter.addVariable("Is Compressed?", session.Compressed);
			reporter.addVariables(Debug.getVariables());
			try
			{
				reporter.addVariable("PTR", mFile.getFilePointer());
			}
			catch ( IOException e1 )
			{
				e1.printStackTrace();
			}
			
			Debug.logCrashDebugOnly(reporter);
			
			mKnownBadSessions.add(session.Id);
		}
		finally
		{
			unlockWrite();
			Profiler.endTimingSection();
			Debug.stopSection();
		}
		
		return null;
	}
	
	/**
	 * Appends records onto a matching session for a particular ownertag.
	 * If there is no session available, a new session will be created
	 * @param records The list of records to append
	 * @return True if append was successful
	 * @throws IllegalStateException if owner tags are not enabled in the log file. You must use the other version of appendRecords()
	 */
	public boolean appendRecords(RecordList records, String owner)
	{
		Debug.loggedAssert(mIsLoaded);
		Debug.loggedAssert(records != null);
		Debug.loggedAssert(owner == null || mHeader.VersionMajor >= 2, "Owner tags are only suppored in version 2 and above");

		if(owner == null && mHeader.RequiresOwnerTags && mHeader.VersionMajor >= 2)
			throw new IllegalStateException("Owner tags are required. You can only append records through appendRecords(records, tag) and tag cannot be null");
		
		if(records.isEmpty())
			return false;
		
		Debug.startSection();
		
		Profiler.beginTimingSection("appendRecords");
		Object synchObject = CrossReferenceIndex.getInstance();
		if(testOverride)
			synchObject = this;
		
		SessionEntry chosenSession = null;
		
		synchronized (synchObject)
		{
			lockWrite();
			
			try
			{
				StructuredFile.beginJointTransaction(CrossReferenceIndex.getInstance(), this);
				
				Debug.info("Appending " + records.size() + " records to " + mPlayerName + ">" + owner);
				
				RecordList recordsToWrite = records;
				while (recordsToWrite != null && recordsToWrite.size() != 0)
				{
					// Get the session to place it
					SessionEntry activeSession = mSessionIndex.getActiveSessionFor(owner);
					if(activeSession != null && mKnownBadSessions.contains(activeSession.Id))
						activeSession = null;
					
					SessionData data;
					
					if(activeSession != null)
						data = mSessionIndex.getDataFor(activeSession);
					else
					{
						Debug.info("No active session for %s>%s. Creating one", mPlayerName, owner);
						data = mSessionIndex.addEmptySession();
						activeSession = data.getIndexEntry();
						mSessionIndex.setActiveSession(owner, activeSession);

						// Apply the ownertag
						activeSession.OwnerTagId = mOwnerTagIndex.getOrCreateTag(owner);
						
						// Apply the correct start date
						activeSession.StartTimestamp = records.getStartTimestamp();
						
						mSessionIndex.set(mSessionIndex.indexOf(activeSession), activeSession);
					}
					
					chosenSession = data.getIndexEntry();
					
					recordsToWrite = data.append(recordsToWrite);
					
					if (recordsToWrite != null)
					{
						// Finalize the existing
						if(!activeSession.Compressed)
							data.compress();
						
						// This session is nolonger able to be used
						mSessionIndex.setActiveSession(owner, null);
					}
				}
				
				StructuredFile.commitJointTransaction(CrossReferenceIndex.getInstance(), this);
				
				return true;
			}
			catch(Throwable e)
			{
				CrashReporter reporter = new CrashReporter(e);
				reporter.setMessage("Appending to session");
				reporter.addVariable("LogFile", mPlayerName);
				reporter.addVariable("Session", chosenSession);
				reporter.addVariable("owner", owner);
				reporter.addVariables(Debug.getVariables());
				
				Debug.logCrash(reporter);
				StructuredFile.rollbackJointTransaction(CrossReferenceIndex.getInstance(), this);
				
				readIndexes();
				return false;
			}
			finally
			{
				Debug.stopSection();
				unlockWrite();
				Profiler.endTimingSection();
			}
		}
	}
	/**
	 * Appends records onto the active session.
	 * If there is no active session, a new session will be created
	 * @param records The list of records to append
	 * @return True if append was successful
	 * @throws IllegalStateException if owner tags are required in the log file. You must use the other version of appendRecords()
	 */
	public boolean appendRecords(RecordList records)
	{
		return appendRecords(records, null);
	}
	
	public Future<Boolean> appendRecordsAsync(RecordList records)
	{
		if(mHeader.RequiresOwnerTags && mHeader.VersionMajor >= 2)
			throw new IllegalStateException("Owner tags are required. You can only append records through appendRecords(records, tag)");
		
		Debug.finest("Submitting appendRecords async task");
		return SpyPlugin.getExecutor().submit(new AppendRecordsTask(this, records));
	}
	public Future<Boolean> appendRecordsAsync(RecordList records, String owner)
	{
		Debug.loggedAssert(mHeader.VersionMajor >= 2, "Owner tags are only suppored in version 2 and above");
		
		Debug.finest("Submitting appendRecords async task");
		return SpyPlugin.getExecutor().submit(new AppendRecordsTask(this, records, owner));
	}
	
	/**
	 * Purges all records between the fromDate inclusive, and the toDate exclusive
	 */
	public boolean purgeRecords(long fromDate, long toDate)
	{
		Debug.loggedAssert( mIsLoaded);
		
		boolean result;
		SessionEntry lastSession = null;
		
		Profiler.beginTimingSection("purgeRecords");
		synchronized(CrossReferenceIndex.getInstance())
		{
			lockWrite();
			
			try
			{
				StructuredFile.beginJointTransaction(CrossReferenceIndex.getInstance(), this);
				
				Debug.info("Purging records from " + Util.dateToString(fromDate) + " to " + Util.dateToString(toDate));
				ArrayList<SessionEntry> relevantEntries = new ArrayList<SessionEntry>();
				
				// Find the relevant sessions
				for(SessionEntry entry : mSessionIndex)
				{
					if((fromDate >= entry.StartTimestamp && fromDate <= entry.EndTimestamp) ||
					   (toDate >= entry.StartTimestamp && toDate <= entry.EndTimestamp) ||
					   (entry.StartTimestamp >= fromDate && entry.StartTimestamp < toDate) ||
					   (entry.EndTimestamp > fromDate && entry.EndTimestamp < toDate))
						relevantEntries.add(entry);
				}
				Debug.finer("  " + relevantEntries.size() + " Matching Sessions");
				
				// Purge data
				for(SessionEntry entry : relevantEntries)
				{
					lastSession = entry;
					String otag = getOwnerTag(entry);
					boolean isAbsolute = otag != null;
					boolean purgeAnyway = false;
					
					if(entry.StartTimestamp < fromDate || entry.EndTimestamp >= toDate)
					{
						int sessionIndex = mSessionIndex.indexOf(entry);
						if(sessionIndex == -1)
							continue;
						
						// Part of the session must be purged
						RecordList sessionData = loadSession(entry);
						if(sessionData != null)
						{
							int startIndex = sessionData.getNextRecordAfter(fromDate);
							int endIndex = sessionData.getLastRecordBefore(toDate);
							
							// Split the data
							RecordList lower = sessionData.splitRecords(startIndex, false);
							sessionData.splitRecords(endIndex, false);
							sessionData.addAll(0, lower);
							//sessionData.splitRecords(endIndex, true);
							//sessionData.removeBefore(startIndex);
							
							// Write back the new updated data
							if(sessionData.size() == 0)
							{
								mSessionIndex.remove(sessionIndex);
								
								if(mSessionIndex.getActiveSessionFor(otag) != null && mSessionIndex.getActiveSessionFor(otag).Id == entry.Id)
									mSessionIndex.setActiveSession(otag, null);
								
								// Pull the proceeding data forward
								pullData(entry.Location);
								
								// So that anything else using this object through a reference, wont do any damage
								entry.RecordCount = 0;
								entry.Location = 0;
								entry.Id = -1;
								entry.TotalSize = 0;
							}
							else
							{
								// Compile the records
								int totalSize = 0;
								for(Record record : sessionData)
									totalSize += record.getSize(isAbsolute);
								
								entry.playerBB = new BoundingBox();
								entry.otherBB = new BoundingBox();
								
								ByteArrayOutputStream bstream = new ByteArrayOutputStream(totalSize);
								DataOutputStream stream = new DataOutputStream(bstream);
								
								long lastSize = stream.size();
								for(Record record : sessionData)
								{
									int expectedSize = record.getSize(isAbsolute);
									if(!record.write(stream,isAbsolute))
										return false;
									
									long actualSize = stream.size() - lastSize;
									
									if(expectedSize != actualSize)
									{
										Debug.severe(record.getType().toString() + " is returning incorrect size. Expected: " + expectedSize + " got " + actualSize);
									}
									lastSize = stream.size();
									
									// Add any location to the location filter 
									if(record instanceof ILocationAware)
									{
										Location location = ((ILocationAware)record).getLocation();
										
										if(location != null)
										{
											if(record instanceof IPlayerLocationAware)
												entry.playerBB.addPoint(location);
											else
												entry.otherBB.addPoint(location);
										}
									}
								}
								
								long oldSize = entry.TotalSize;
								
								// Write to file
								mFile.seek(entry.Location);
								mFile.write(bstream.toByteArray());
								
								// Update the session header
								entry.Compressed = false;
								entry.StartTimestamp = sessionData.getStartTimestamp();
								entry.EndTimestamp = sessionData.getEndTimestamp();
								entry.RecordCount = (short) sessionData.size();
								entry.TotalSize = totalSize;
								
								mSessionIndex.set(sessionIndex,entry);
								CrossReferenceIndex.updateSession(this, entry);
								
								mSpaceLocator.releaseSpace(entry.Location + entry.TotalSize, oldSize - entry.TotalSize);
								
								// Pull the proceeding data forward
								pullData(entry.Location + entry.TotalSize);
								
							}
						}
						else
							purgeAnyway = true; // Corrupt sessions should just be purged
					}
					
					if(purgeAnyway || entry.StartTimestamp >= fromDate && entry.EndTimestamp < toDate)
					{
						// Whole session must be purged
						int index = mSessionIndex.indexOf(entry);
						if(index == -1)
							continue;
						
						mSessionIndex.remove(index);
						
						if(mSessionIndex.getActiveSessionFor(otag) != null && mSessionIndex.getActiveSessionFor(otag).Id == entry.Id)
							mSessionIndex.setActiveSession(otag, null);
	
						// Pull the proceeding data forward
						pullData(entry.Location);
						
						// Purge the owner tag if no session uses it
						int count = 0;
						for(SessionEntry session : mSessionIndex)
						{
							if(session.Id == entry.Id)
								continue;
							if(session.OwnerTagId == entry.OwnerTagId)
								count++;
						}
						
						if(count == 0)
							mOwnerTagIndex.remove(mOwnerTagIndex.getOwnerTagById(entry.OwnerTagId));
						
						// So that anything else using this object through a reference, wont do any damage
						entry.RecordCount = 0;
						entry.Location = 0;
						entry.Id = -1;
						entry.TotalSize = 0;
						entry.OwnerTagId = -1;
					}
				}
				
				result = true;
				
				StructuredFile.commitJointTransaction(CrossReferenceIndex.getInstance(), this);
			}
			catch (Throwable e)
			{
				CrashReporter reporter = new CrashReporter(e);
				reporter.setMessage("Purging");
				reporter.addVariable("LogFile", mPlayerName);
				reporter.addVariable("fromDate", fromDate);
				reporter.addVariable("toDate", toDate);
				reporter.addVariable("lastSession", lastSession);
				
				Debug.logCrash(reporter);
				
				result = false;
				StructuredFile.rollbackJointTransaction(CrossReferenceIndex.getInstance(), this);
				
				readIndexes();
			}
			finally
			{
				unlockWrite();
			}
		}
		Profiler.endTimingSection();
		return result;
	}

	
	public void setRollbackState(SessionEntry session, List<Short> indices, boolean state)
	{
		lockWrite();
		
		try
		{
			mFile.beginTransaction();
			
			mRollbackIndex.setRollbackState(session, indices, state);
			
			mFile.commit();
		}
		catch(Throwable e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Applying Rollback State");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("session", session.Id);
			reporter.addVariable("state", state);
			reporter.addVariable("indices", indices);
			
			Debug.logCrash(reporter);
			
			mFile.rollback();
			readIndexes();
		}
		finally
		{
			unlockWrite();
		}
	}

	
	/**
	 * Remember to call this any time the file is rolled back, otherwise the indexes and header which store stuff in ram too, will be in an inconsistent state
	 */
	private void readIndexes()
	{
		try
		{
			mFile.seek(0);
			mHeader.read(mFile);
			
			// Read the indices
			mHoleIndex.read();
			
			if(mHeader.VersionMajor >= 2)
				mOwnerTagIndex.read();
	
			// So that active sessions are mapped correctly
			mSessionIndex.read();
			
			if(mHeader.VersionMajor >= 3)
				mRollbackIndex.read();
			
			if(mHeader.VersionMajor >= 4)
				mChunkIndex.read();
			
			readTags();
		}
		catch(IOException e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Read Indexes");
			reporter.addVariable("LogFile", mPlayerName);
			
			Debug.logCrash(reporter);
		}
	}
	/**
	 *  Gets the name of the player whose activities are recorded here
	 */
	public String getName()
	{
		return mPlayerName;
	}
	
	public NBTTagCompound getTags()
	{
		return mTag;
	}
	
	public void setTags(NBTTagCompound tags)
	{
		mTag = tags;
	}
	
	public void saveTags()
	{
		lockWrite();
		try
		{
			mFile.beginTransaction();
			
			if(mTag != null)
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream();
			
				NBTCompressedStreamTools.writeCompressed(mTag, output);
				
				if(mHeader.TagSize <= output.size())
				{
					Debug.finer("Shrinking tags from %X->%X to %X->%X", mHeader.TagLocation, mHeader.TagLocation + mHeader.TagSize - 1, mHeader.TagLocation, mHeader.TagLocation + output.size() - 1);
					
					mFile.seek(mHeader.TagLocation);
					mFile.write(output.toByteArray());
					
					// Free remaining space
					mSpaceLocator.releaseSpace(mHeader.TagLocation + output.size(), mHeader.TagSize - output.size());
					
					mHeader.TagSize = output.size();
				}
				else
				{
					if(mSpaceLocator.isFreeSpace(mHeader.TagLocation + mHeader.TagSize, output.size() - mHeader.TagSize))
					{
						Debug.finer("Expanding tags from %X->%X to %X->%X", mHeader.TagLocation, mHeader.TagLocation + mHeader.TagSize - 1, mHeader.TagLocation, mHeader.TagLocation + output.size() - 1);
						mSpaceLocator.consumeSpace(mHeader.TagLocation + mHeader.TagSize, output.size() - mHeader.TagSize);
						
						// Expand
						mFile.seek(mHeader.TagLocation);
						mFile.write(output.toByteArray());
					}
					else
					{
						// Relocate
						mSpaceLocator.releaseSpace(mHeader.TagLocation, mHeader.TagSize);
						long oldLocation = mHeader.TagLocation;
						long oldSize = mHeader.TagSize;
						
						mHeader.TagLocation = mSpaceLocator.findFreeSpace(output.size());
						mSpaceLocator.consumeSpace(mHeader.TagLocation, output.size());
						mHeader.TagSize = output.size();
						
						mFile.seek(mHeader.TagLocation);
						mFile.write(output.toByteArray());
						
						Debug.finer("Relocating tags from %X->%X to %X->%X", oldLocation, oldLocation + oldSize - 1, mHeader.TagLocation, mHeader.TagLocation + mHeader.TagSize - 1);
					}
				}
			}
			else if(mHeader.TagSize != 0)
			{
				Debug.finer("Clearing tags from %X->%X", mHeader.TagLocation, mHeader.TagLocation + mHeader.TagSize - 1);
				mSpaceLocator.releaseSpace(mHeader.TagLocation, mHeader.TagSize);
				mHeader.TagSize = 0;
			}
			
			mFile.seek(0);
			mHeader.write(mFile);
			
			mFile.commit();
		}
		catch(Throwable e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Applying tags");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("Tag", mTag);
			reporter.addVariable("From", mHeader.TagLocation);
			
			Debug.logCrash(reporter);
			
			
			mFile.rollback();
			readIndexes();
		}
		finally
		{
			unlockWrite();
		}
	}
	
	private void readTags()
	{
		lockRead();
		try
		{
			if(mHeader.TagSize != 0)
			{
				byte[] data = new byte[(int)mHeader.TagSize];
				mFile.seek(mHeader.TagLocation);
				mFile.readFully(data);
				
				ByteArrayInputStream input = new ByteArrayInputStream(data);
				
				mTag = NBTCompressedStreamTools.readCompressed(input);
			}
			else
				mTag = null;
		}
		catch(Throwable e)
		{
			CrashReporter reporter = new CrashReporter(e);
			reporter.setMessage("Reading tags");
			reporter.addVariable("LogFile", mPlayerName);
			reporter.addVariable("Tag", mTag);
			reporter.addVariable("From", mHeader.TagLocation);
			reporter.addVariable("Size", mHeader.TagSize);
			
			Debug.logCrash(reporter);
		}
		finally
		{
			unlockRead();
		}
	}

	private int mReferenceCount = 0;
	
	private ReentrantLock mReferenceLock;
	
	private boolean mIsLoaded = false;
	private boolean mIsCorrupt = false;
	private boolean mIsClosing = false;
	private int mTimeoutId = -1;
	private String mPlayerName;
	
	SessionIndex mSessionIndex;
	HoleIndex mHoleIndex;
	OwnerTagIndex mOwnerTagIndex;
	RollbackIndex mRollbackIndex;
	ChunkIndex mChunkIndex;
	
	private SpaceLocator mSpaceLocator;
	
	private FileHeader mHeader;
	
	public static boolean sNoTimeoutOverride = false;
	
	private NBTTagCompound mTag;
	
	/** Used for testing so that the file is used in isolation */
	public boolean testOverride = false;
	
	private HashSet<Integer> mKnownBadSessions = new HashSet<Integer>();
	
	// Task for closing the logfile when everything is executed
	private class CloseTask extends Task<Void> 
	{
		@Override
		public Void call() 
		{
			Debug.info("Closing log " + mPlayerName);
			
			try 
			{
				mFile.close();
			} 
			catch (IOException e) 
			{
				Debug.logException(e);
			}
			
			mIsLoaded = false;
			
			Debug.finest("CloseTask is completed");
			
			return null;
		}

		@Override
		public int getTaskTargetId()
		{
			return mPlayerName.hashCode();
		}

		@Override
		public au.com.mineauz.PlayerSpy.LogTasks.Task.Priority getTaskPriority()
		{
			return Priority.Normal;
		}
	}

	public int getVersionMajor() 
	{
		return mHeader.VersionMajor;
	}
	public int getVersionMinor() 
	{
		return mHeader.VersionMinor;
	}
}
