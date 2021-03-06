package au.com.mineauz.PlayerSpy.structurefile;

import java.io.IOException;
import java.io.RandomAccessFile;


// Represents an empty 
public class HoleEntry extends IndexEntry
{
	public static final int cSize = 8;
	
	// The absolute location of the hole
	public long Location;
	// The size of the hole
	public long Size;
	
	public void write(RandomAccessFile file) throws IOException
	{
		file.writeInt((int)Location);
		file.writeInt((int)Size);
	}
	
	public void read(RandomAccessFile file) throws IOException
	{
		Location = (long)file.readInt();
		Size = (long)file.readInt();
	}
}