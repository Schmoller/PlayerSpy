package au.com.mineauz.PlayerSpy.Utilities;

import java.util.ArrayList;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public class Pager 
{
	private String mName;
	private ArrayList<String> mItems;
	private int mItemsPerPage;
	public Pager(String name, int itemsPerPage)
	{
		mItems = new ArrayList<String>();
		mItemsPerPage = itemsPerPage;
		mName = name;
	}
	
	public void addItem(String item)
	{
		mItems.add(item);
	}
	
	public int getPageCount()
	{
		return (int) Math.ceil(mItems.size() / (float)mItemsPerPage);
	}
	
	public boolean isLastPageFull()
	{
		int items = mItems.size() - ((getPageCount() - 1) * mItemsPerPage);
		
		return (items == mItemsPerPage);
	}
	
	public void displayPage(CommandSender sender, int page)
	{
		sender.sendMessage(mName);
		sender.sendMessage(ChatColor.RED + "------------------ Page " + ChatColor.YELLOW + (page + 1) + ChatColor.RED + " / " + ChatColor.YELLOW + getPageCount() + ChatColor.RED + "--------");
		
		for(int i = page * mItemsPerPage; i < mItems.size() && i < (page + 1) * mItemsPerPage; ++i)
		{
			sender.sendMessage(mItems.get(i));
		}
		sender.sendMessage(ChatColor.RED + "--------------------------------");
	}
	
	public int getItemCount()
	{
		return mItems.size();
	}
	
	public int getItemsPerPage()
	{
		return mItemsPerPage;
	}
}
