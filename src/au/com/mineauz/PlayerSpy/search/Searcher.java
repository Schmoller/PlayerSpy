package au.com.mineauz.PlayerSpy.search;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import au.com.mineauz.PlayerSpy.Cause;
import au.com.mineauz.PlayerSpy.Pager;
import au.com.mineauz.PlayerSpy.Pair;
import au.com.mineauz.PlayerSpy.SpyPlugin;
import au.com.mineauz.PlayerSpy.Utility;
import au.com.mineauz.PlayerSpy.Records.Record;

public class Searcher 
{
	public static final Searcher instance = new Searcher();
	
	private HashMap<CommandSender, Future<SearchResults>> mWaitingTasks;
	private HashMap<CommandSender, SearchResults> mCachedResults;
	private HashMap<CommandSender, Boolean> mCachedResultsAreSearch;
	
	
	private Searcher() 
	{
		mWaitingTasks = new HashMap<CommandSender, Future<SearchResults>>();
		mCachedResults = new HashMap<CommandSender, SearchResults>();
		mCachedResultsAreSearch = new HashMap<CommandSender, Boolean>();
	}
	
	public void update()
	{
		Iterator<Entry<CommandSender, Future<SearchResults>>> it = mWaitingTasks.entrySet().iterator();
		while(it.hasNext())
		{
			Entry<CommandSender, Future<SearchResults>> entry = it.next();
			
			if(entry.getValue().isDone())
			{
				try 
				{
					mCachedResults.put(entry.getKey(), entry.getValue().get());
					displayResults(entry.getKey(), 0);
				} 
				catch (InterruptedException e) 
				{
					e.printStackTrace();
				} 
				catch (ExecutionException e) 
				{
					e.printStackTrace();
				}
				it.remove();
			}
		}
	}
	
	public void searchAndDisplay(CommandSender sender, SearchFilter filter)
	{
		SearchTask task = new SearchTask(filter);
		mWaitingTasks.put(sender, SpyPlugin.getExecutor().submit(task));
		mCachedResultsAreSearch.put(sender, true);
	}
	public void getBlockHistory(CommandSender sender, SearchFilter filter)
	{
		SearchTask task = new SearchTask(filter);
		mWaitingTasks.put(sender, SpyPlugin.getExecutor().submit(task));
		mCachedResultsAreSearch.put(sender, false);
	}
	public void displayResults(CommandSender who, int page)
	{
		if(!mCachedResults.containsKey(who))
			return;
		
		if(who instanceof Player && !(((Player)who).isOnline()))
		{
			mCachedResults.remove(who);
			return;
		}
		
		SearchResults results = mCachedResults.get(who);
		String title;
		if(mCachedResultsAreSearch.get(who))
			title = "Search results";
		else
			title = "Block history";
		
		Date lastDate = new Date(0);
		Pager pager = new Pager(title, (who instanceof Player ? 10 : 40));
		int lastPageCount = 0;
		for(Pair<Record,Integer> result : results.allRecords)
		{
			String msg = result.getArg1().getDescription();
			
			if(msg == null)
				continue;
			
			// Do date stuff
			Date date = new Date(result.getArg1().getTimestamp());
			Date dateOnly = Utility.getDatePortion(date);
			date.setTime(date.getTime() - dateOnly.getTime());
			// Output the date if it has changed
			if(lastDate.getTime() != dateOnly.getTime() || pager.getPageCount() != lastPageCount)
			{
				if(dateOnly.getTime() == Utility.getDatePortion(new Date()).getTime())
					pager.addItem(ChatColor.GREEN + "Today");
				else
				{
					DateFormat fmt = DateFormat.getDateInstance(DateFormat.FULL);
					pager.addItem(ChatColor.GREEN + fmt.format(dateOnly));
				}
				lastDate = dateOnly;
				
				lastPageCount = pager.getPageCount();
			}
			SimpleDateFormat fmt = new SimpleDateFormat("hh:mma");
			
			Cause cause = results.causes.get(result.getArg2());
			
			String output = String.format(ChatColor.GREEN + " %7s " + ChatColor.RESET, fmt.format(date)) + String.format(msg, ChatColor.RED + cause.friendlyName() + ChatColor.RESET);
			
			pager.addItem(output);
		}
		
		pager.displayPage(who, page);
		who.sendMessage(ChatColor.GOLD + "Use '/ps page " + (page + 2) + "' to view the next page");
		
	}
	
}
