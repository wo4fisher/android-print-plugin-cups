/*
    Copyright (C) 2014 Sergii Pylypenko.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.cups.android;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.text.Editable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.content.res.Configuration;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.view.View.OnKeyListener;
import android.view.MenuItem;
import android.view.Menu;
import android.view.Gravity;
import android.text.method.TextKeyListener;
import java.util.LinkedList;
import java.io.SequenceInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.zip.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Set;
import android.text.SpannedString;
import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import android.view.inputmethod.InputMethodManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import java.util.concurrent.Semaphore;
import android.content.pm.ActivityInfo;
import android.view.Display;
import android.text.InputType;
import android.util.Log;
import android.view.Surface;
import android.app.ProgressDialog;
import android.printservice.*;
import android.print.*;
import java.util.*;
import android.os.Environment;
import android.os.StatFs;

public class Cups
{
	static String IMG = "img";
	static String PROOT = "./proot.sh";
	static String CUPSD = "/usr/sbin/cupsd";
	static String LP = "/usr/bin/lp";
	static String LPSTAT = "/usr/bin/lpstat";
	static String LPOPTIONS = "/usr/bin/lpoptions";
	static String LPINFO = "/usr/sbin/lpinfo";
	static String LPADMIN = "/usr/sbin/lpadmin";
	static Process cupsd = null;

	static File chrootPath(Context p)
	{
		return new File(p.getFilesDir(), IMG);
	}

	synchronized static boolean isInstalled(Context p)
	{
		return new File(p.getFilesDir().getAbsolutePath() + "/" + IMG + CUPSD).exists();
	}

	synchronized static boolean isRunning(Context p)
	{
		Proc pp = new Proc(new String[] {PROOT, LPSTAT, "-r"}, chrootPath(p));
		return pp.out.length > 0 && pp.out[0].equals("scheduler is running");
	}

	synchronized static String[] getPrinters(Context p)
	{
		Proc pp = new Proc(new String[] {PROOT, LPSTAT, "-v"}, chrootPath(p));
		String[] ret = new String[pp.out.length];
		for (int i = 0; i < pp.out.length; i++)
		{
			if (!pp.out[i].startsWith("device for ") || pp.out[i].indexOf(":") == -1)
				return new String[0];
			ret[i] = pp.out[i].substring(("device for ").length(), pp.out[i].indexOf(":"));
		}
		return ret;
	}

	synchronized static int getPrinterStatus(Context p, String printer)
	{
		Proc pp = new Proc(new String[] {PROOT, LPSTAT, "-p", printer}, chrootPath(p));
		if (pp.out.length == 0 || pp.status != 0)
			return PrinterInfo.STATUS_UNAVAILABLE;
		if (pp.out[0].indexOf("is idle") != -1)
			return PrinterInfo.STATUS_IDLE;
		return PrinterInfo.STATUS_BUSY;
	}

	synchronized static Map<String, String[]> getPrinterOptions(Context p, String printer)
	{
		HashMap<String, String[]> ret = new HashMap<String, String[]>();
		Proc pp = new Proc(new String[] {PROOT, LPOPTIONS, "-p", printer, "-l"}, chrootPath(p));
		if (pp.out.length == 0 || pp.status != 0)
			return ret;
		for(String s: pp.out)
		{
			if (s.indexOf("/") == -1 || s.indexOf(": ") == -1)
				continue;
			String k = s.substring(0, s.indexOf("/"));
			String vv[] = s.substring(s.indexOf(": ") + 2).split("\\s+");
			for (int i = 0; i < vv.length; i++)
			{
				if (vv[i].startsWith("*"))
				{
					String dd = vv[i].substring(1);
					vv[i] = vv[0];
					vv[0] = dd;
					Log.d(TAG, "Printer " + printer + " option " + k + " default value " + dd);
					break;
				}
			}
			ret.put(k, vv);
		}
		return ret;
	}

	private static Map<String, PrintAttributes.MediaSize> mediaSizes = null;

	synchronized static PrintAttributes.MediaSize getMediaSize(Context p, String name)
	{
		if (mediaSizes == null)
			fillMediaSizes(p);
		return mediaSizes.get(name);
	}

	private static void fillMediaSizes(Context p)
	{
		mediaSizes = new HashMap<String, PrintAttributes.MediaSize>();
		try
		{
			BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(
				chrootPath(p).getAbsolutePath() + "/" + IMG + "/usr/share/cups/ppdc/media.defs")));
			String line;
			while((line = in.readLine()) != null)
			{
				if (!line.startsWith("#media \"") || line.indexOf("/") == -1)
					continue;
				int slash = line.indexOf("/");
				String name = line.substring("#media \"".length(), slash);
				String descr = line.substring(slash + 1, line.indexOf("\"", slash + 1));
				String sizes[] = line.substring(line.indexOf("\"", slash + 1)).split("\\s+");
				if (sizes.length < 2)
					continue;
				int w = Integer.parseInt(sizes[0]);
				int h = Integer.parseInt(sizes[1]);
				Log.d(TAG, "fillMediaSizes: " + name + " desc '" + descr + "' size " + w + "x" + h);
				mediaSizes.put(name, new PrintAttributes.MediaSize(name, descr, w, h));
			}
			in.close();
		}
		catch(Exception e)
		{
			Log.i(TAG, "Error reading media sizes: " + e.toString());
		}
	}

	static PrintAttributes.Resolution getResolution(String s)
	{
		String rr[] = s.split("[^0-9]+");
		if (rr.length == 0)
			return new PrintAttributes.Resolution(s, s, 300, 300);
		if (rr.length == 1)
			return new PrintAttributes.Resolution(s, s, Integer.parseInt(rr[0]), Integer.parseInt(rr[0]));
		return new PrintAttributes.Resolution(s, s, Integer.parseInt(rr[0]), Integer.parseInt(rr[1]));
	}

	synchronized static void addWindowsSharedPrinter(Context p, String model, String host, String printer, String workgroup, String username, String password)
	{
		String url = "smb://";
		if (username.length() > 0 && password.length() > 0)
			url = url + username + ":" + password + "@";
		if (workgroup.length() > 0)
			url = url + workgroup + "/";
		url = url + host + "/";
		url = url + printer;
		new Proc(new String[] {PROOT, LPADMIN, "-p", url, "-m", model}, chrootPath(p));
	}

	synchronized static String[] getPrinterModels(Context p)
	{
		return new Proc(new String[] {PROOT, LPINFO, "-m"}, chrootPath(p)).out;
	}

	synchronized static void startCupsDaemon(Context p)
	{
		if (cupsd != null)
			return;
		restartCupsDaemon(p);
	}

	synchronized static void stopCupsDaemon(Context p)
	{
		if (cupsd == null)
			return;
		cupsd.destroy();
		cupsd = null;
	}

	synchronized static void restartCupsDaemon(Context p)
	{
		if (cupsd != null)
			cupsd.destroy();
		cupsd = Runtime.getRuntime().exec(new String[] {PROOT, CUPSD, "-f"}, null, chrootPath(p));
	}

	synchronized static void unpackData(final MainActivity p, final TextView text)
	{
		if (isInstalled(p))
		{
			p.enableSettingsButton();
			return;
		}

		Log.i(TAG, "Extracting CUPS data");

		setText(p, text, p.getResources().getString(R.string.please_wait_unpack));

		StatFs storage = new StatFs(p.getFilesDir().getPath());
		long avail = (long)storage.getAvailableBlocks() * storage.getBlockSize() / 1024 / 1024;
		long needed = 360;
		if (avail < needed)
		{
			setText(p, text, p.getResources().getString(R.string.not_enough_space, needed, avail));
			return;
		}

		try
		{
			p.getFilesDir().mkdirs();
			InputStream stream = p.getAssets().open("busybox-" + android.os.Build.CPU_ABI);
			String busybox = new File(p.getFilesDir(), "busybox").getAbsolutePath();
			OutputStream out = new FileOutputStream(busybox);

			copyStream(stream, out);

			new Proc(new String[] {"/system/bin/chmod", "0755", busybox}, p.getFilesDir());

			try
			{
				InputStream archiveAssets = p.getAssets().open("dist-cups-wheezy.tar.xz");
				Process proc = Runtime.getRuntime().exec(new String[] {busybox, "tar", "xJ"}, null, p.getFilesDir());
				copyStream(archiveAssets, proc.getOutputStream());
				proc.waitFor();
			}
			catch(Exception e)
			{
				Log.i(TAG, "No data archive in assets, trying OBB data");
				new Proc(new String[] {busybox, "tar", "xJf",
							Environment.getExternalStorageDirectory().getAbsolutePath() +
							"/Android/obb/" + p.getPackageName() + "/main.100." + p.getPackageName() + ".obb"});
			}

			new Proc(new String[] {busybox, "cp", "-a", "img-" + android.os.Build.CPU_ABI + "/.", "img/"});
			new Proc(new String[] {busybox, "rm", "-rf", "img-armeabi-v7a", "img-x86"});
			stream = p.getAssets().open("cupsd.conf");
			out = new FileOutputStream(new File(chrootPath(p), "etc/cups/cupsd.conf"));
			copyStream(stream, out);

			Log.i(TAG, "Extracting data finished");
		}
		catch( java.io.IOException e )
		{
			Log.i(TAG, "Error extracting data: " + e.toString());
			setText(p, text, p.getResources().getString(R.string.error_extracting) + e.toString());
			return;
		}

		setText(p, text, p.getResources().getString(R.string.add_printers));
		p.enableSettingsButton();
		startCupsDaemon(p);
	}

	static void setText(final Activity p, final TextView text, final String str)
	{
		p.runOnUiThread(new Runnable()
		{
			public void run()
			{
				text.setText(str);
			}
		});
	}

	static void copyStream(InputStream stream, OutputStream out) throws java.io.IOException
	{
		byte[] buf = new byte[16384];
		int len = stream.read(buf);
		while (len >= 0)
		{
			if(len > 0)
				out.write(buf, 0, len);
			len = stream.read(buf);
		}
		out.close();
		stream.close();
	}

	static final String TAG = "Cups";
}