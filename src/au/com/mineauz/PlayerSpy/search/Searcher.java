package au.com.mineauz.PlayerSpy.search;

import java.text.DateFormat;
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
import au.com.mineauz.PlayerSpy.SpyPlugin;
import au.com.mineauz.PlayerSpy.Records.Record;
import au.com.mineauz.PlayerSpy.Utilities.Pager;
import au.com.mineauz.PlayerSpy.Utilities.Pair;
import au.com.mineauz.PlayerSpy.Utilities.Utility;

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
		
		long lastDate = 0;
		Pager pager = new Pager(title, (who instanceof Player ? 10 : 40));
		for(Pair<Record,Integer> result : results.allRecords)
		{
			String msg = result.getArg1().getDescription();
			
			if(msg == null)
				continue;
			
			// Do date stuff
			long date = result.getArg1().getTimestamp();
			
			long dateOnly = Utility.getDatePortion(date);
			date = Utility.getTimePortion(date);
			
			// Output the date if it has changed
			if(lastDate != dateOnly ||  ((int) Math.ceil((pager.getItemCount()+1) / (float)pager.getItemsPerPage()) != pager.getPageCount()))
			{
				if(dateOnly == Utility.getDatePortion(System.currentTimeMillis()))
					pager.addItem(ChatColor.GREEN + "Today");
				else
				{
					DateFormat fmt = DateFormat.getDateInstance(DateFormat.FULL);
					fmt.setTimeZone(SpyPlugin.getSettings().timezone);
					pager.addItem(ChatColor.GREEN + fmt.format(new Date(dateOnly)));
				}
				lastDate = dateOnly;
			}
			
			Cause cause = results.causes.get(result.getArg2());

			String modifierOutput = "";
			for(Modifier mod : results.usedFilter.modifiers)
			{
				String temp = mod.getExtraData(result.getArg1());
				if(temp != null)
					modifierOutput += "\n" + temp.trim();
			}
			String output = String.format(ChatColor.GREEN + " %7s " + ChatColor.RESET, Utility.formatTime(date, "hh:mma")) + String.format(msg, ChatColor.RED + cause.friendlyName() + ChatColor.RESET) + modifierOutput;
			
			pager.addItem(output);
		}
		
		pager.displayPage(who, page);
		if(page < pager.getPageCount()-1)
		{
			if(mCachedResultsAreSearch.get(who))
				who.sendMessage(ChatColor.GOLD + "Use '/ps search " + (page + 2) + "' to view the next page");
			else
				who.sendMessage(ChatColor.GOLD + "Use '/ps history " + (page + 2) + "' to view the next page");
		}
		
	}

	public boolean hasResults( CommandSender sender, boolean isSearch )
	{
		if(!mCachedResults.containsKey(sender))
			return false;
		
		if(mCachedResultsAreSearch.get(sender) && isSearch)
			return true;
		else if(!mCachedResultsAreSearch.get(sender) && !isSearch)
			return true;
		
		return false;
	}
	
}
