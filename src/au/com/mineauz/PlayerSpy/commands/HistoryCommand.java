package au.com.mineauz.PlayerSpy.commands;

import java.util.ArrayDeque;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import au.com.mineauz.PlayerSpy.fsa.*;
import au.com.mineauz.PlayerSpy.inspect.Inspector;
import au.com.mineauz.PlayerSpy.search.*;

public class HistoryCommand implements ICommand
{
	private static State mStartState;
	
	static
	{
		buildState();
	}
	
	private static void buildState()
	{
		State terminator = new FinalCompactorDA().addNext(new FinalState());
		
		State dateEnd = new DateConstraintDA().addNext(terminator);
		
		
		State beforeTimeState = new StringState("before")
			.addNext(new DateState()
				.addNext(new TimeState()
					.addNext(new DateCompactorDA()
						.addNext(dateEnd)
					)
				)
				.addNext(dateEnd)
			)
			.addNext(new TimeState()
				.addNext(new TimeOnlyDA()
					.addNext(dateEnd)
				)
			)
		;
		State afterTimeState = new StringState("after")
			.addNext(new DateState()
				.addNext(new TimeState()
					.addNext(new DateCompactorDA()
						.addNext(dateEnd)
					)
				)
				.addNext(dateEnd)
			)
			.addNext(new TimeState()
				.addNext(new TimeOnlyDA()
					.addNext(dateEnd)
				)
			)
		;
		
		State betweenPt2 = new StringState("and")
			.addNext(new DateState()
				.addNext(new TimeState()
					.addNext(new DateCompactorDA()
						.addNext(dateEnd)
					)
				)
				.addNext(dateEnd)
			)
			.addNext(new TimeState()
				.addNext(new TimeOnlyDA()
					.addNext(dateEnd)
				)
			)
		;
		
		State betweenTimeState = new StringState("between")
			.addNext(new DateState()
				.addNext(new TimeState()
					.addNext(new DateCompactorDA()
						.addNext(betweenPt2)
					)
				)
				.addNext(betweenPt2)
			)
			.addNext(new TimeState()
				.addNext(new TimeOnlyDA()
					.addNext(betweenPt2)
				)
			)
		;
		
		State timeConstraint = new NullState()
			.addNext(beforeTimeState)
			.addNext(afterTimeState)
			.addNext(betweenTimeState)
			.addNext(terminator)
		;
		
		
		State limitTo = new NullState()
			.addNext(new StringState("limit")
				.addNext(new StringState("to")
					.addNext(new CauseState()
						.addNext(timeConstraint)
					)
				)
			)
			.addNext(timeConstraint)
		;
		
		State hideShowDA = new RecordFilterDA().addNext(limitTo);
		
		State hideState = new StringState("hide")
			.addNext(new MultiStringState("blocks","block")
				.addNext(hideShowDA)
			)
			.addNext(new MultiStringState("items","item","transactions","inventory","inventories")
				.addNext(hideShowDA)
			)
			.addNext(new MultiStringState("interact","interactions")
				.addNext(hideShowDA)
			)
		;
		
		State onlyState = new StringState("only")
			.addNext(new MultiStringState("blocks","block")
			.addNext(hideShowDA)
			)
			.addNext(new MultiStringState("items","item","transactions","inventory","inventories")
				.addNext(hideShowDA)
			)
			.addNext(new MultiStringState("interact","interactions")
				.addNext(hideShowDA)
			)
		;
		
		mStartState = new InitialState()
			.addNext(onlyState)
			.addNext(hideState)
			.addNext(limitTo)
		;
	}
	@Override
	public String getName() 
	{
		return "history";
	}

	@Override
	public String[] getAliases() 
	{
		return null;
	}

	@Override
	public String getPermission() 
	{
		return "playerspy.inspect";
	}

	@Override
	public String getUsageString(String label) 
	{
		return label + ChatColor.GREEN + "[other args] [page]";
	}

	@Override
	public boolean canBeConsole() {	return false; }

	@Override
	public boolean onCommand(CommandSender sender, String label, String[] args) 
	{
		if(Inspector.instance.getSelectedBlock((Player)sender) == null)
		{
			sender.sendMessage(ChatColor.RED + "You need to select a block with inspect mode");
			return false;
		}
		
		// Collapse the args into a single string
		String inputString = "";
		for(int i = 0; i < args.length; i++)
		{
			if(i != 0)
				inputString += " ";
			inputString += args[i];
		}
		
		try
		{
			ArrayDeque<Object> results = FiniteStateAutomata.parse(inputString.toLowerCase(), mStartState);
			SearchFilter filter = (SearchFilter)results.pop();
			filter.andConstraints.add(new DistanceConstraint(0.9, Inspector.instance.getSelectedBlock((Player)sender)));
			
			Searcher.instance.getBlockHistory(sender, filter);
		}
		catch(ParseException e)
		{
			sender.sendMessage(ChatColor.RED + e.getMessage());
		}
		
		return true;
	}
	
}
