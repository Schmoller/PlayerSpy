package au.com.mineauz.PlayerSpy.search;

import java.util.ArrayList;

import au.com.mineauz.PlayerSpy.Cause;
import au.com.mineauz.PlayerSpy.search.interfaces.Constraint;
import au.com.mineauz.PlayerSpy.search.interfaces.Modifier;

public class SearchFilter 
{
	public ArrayList<Constraint> orConstraints = new ArrayList<Constraint>();
	public ArrayList<Constraint> andConstraints = new ArrayList<Constraint>();
	public ArrayList<Cause> causes = new ArrayList<Cause>();
	public ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
}
