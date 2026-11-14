using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Threading;

// Test double for java.exe, javaw.exe, javac.exe and powershell.exe. The file name decides which tool it plays. The JDK tools take
// their facts from the release file next to the bin directory, so a test only has to write that file.
internal static class StubTool
{
    private static int Main(string[] args)
    {
        string exe = Assembly.GetExecutingAssembly().Location;
        switch (Path.GetFileNameWithoutExtension(exe).ToLowerInvariant())
        {
            case "powershell":
                return PlayPowerShell(args);
            case "javac":
                return PlayJavac(exe);
            default:
                return PlayJava(exe, args);
        }
    }

    private static Dictionary<string, string> ReadRelease(string exe)
    {
        string home = Path.GetDirectoryName(Path.GetDirectoryName(exe));
        Dictionary<string, string> values = new Dictionary<string, string>();
        string path = Path.Combine(home, "release");
        if (File.Exists(path))
        {
            foreach (string line in File.ReadAllLines(path))
            {
                int equals = line.IndexOf('=');
                if (equals > 0)
                {
                    values[line.Substring(0, equals)] = line.Substring(equals + 1).Trim('"');
                }
            }
        }
        values["JDK_HOME"] = home;
        return values;
    }

    private static string Get(Dictionary<string, string> values, string key, string fallback)
    {
        string value;
        return values.TryGetValue(key, out value) ? value : fallback;
    }

    private static int PlayJava(string exe, string[] args)
    {
        Dictionary<string, string> release = ReadRelease(exe);
        if (Get(release, "STUB_HANG", "") == "true")
        {
            Thread.Sleep(Timeout.Infinite);
        }
        bool settings = Array.IndexOf(args, "-XshowSettings:properties") >= 0;
        if (!settings && Array.IndexOf(args, "-version") < 0)
        {
            return PlayApplication(exe, release, args);
        }

        string version = Get(release, "JAVA_VERSION", "0");
        string runtimeVersion = Get(release, "JAVA_RUNTIME_VERSION", version);
        string osArch = Get(release, "OS_ARCH", "x86_64");
        string arch = osArch == "x86_64" ? "amd64" : osArch;
        if (settings)
        {
            Console.Error.WriteLine("Property settings:");
            Console.Error.WriteLine("    java.home = " + release["JDK_HOME"]);
            Console.Error.WriteLine("    java.runtime.version = " + runtimeVersion);
            Console.Error.WriteLine("    java.specification.version = " + version.Split('.')[0]);
            Console.Error.WriteLine("    java.vendor = " + Get(release, "IMPLEMENTOR", "Stub Vendor"));
            Console.Error.WriteLine("    java.version = " + version);
            Console.Error.WriteLine("    os.arch = " + arch);
            Console.Error.WriteLine("    sun.arch.data.model = " + (arch == "x86" ? "32" : "64"));
            Console.Error.WriteLine("");
        }
        Console.Error.WriteLine("openjdk version \"" + version + "\"");
        Console.Error.WriteLine("OpenJDK Runtime Environment (build " + runtimeVersion + ")");
        return 0;
    }

    // What a started application does: says how it was started, writes to standard output and standard error in turn (so a test
    // can see that the log keeps their order), may stay running for a while, and exits with the requested code.
    private static int PlayApplication(string exe, Dictionary<string, string> release, string[] args)
    {
        Console.Out.WriteLine("STUB-LAUNCH");
        Console.Out.WriteLine("STUB-EXE " + exe);
        Console.Out.WriteLine("STUB-PID " + System.Diagnostics.Process.GetCurrentProcess().Id);
        foreach (string arg in args)
        {
            Console.Out.WriteLine("STUB-ARG " + arg);
        }
        Console.Out.WriteLine("STUB-OUT-1");
        Console.Error.WriteLine("STUB-ERR-1");
        Console.Out.WriteLine("STUB-OUT-2");
        Console.Error.WriteLine("STUB-ERR-2");
        int sleep = int.Parse(Get(release, "STUB_SLEEP_MS", "0"));
        if (sleep > 0)
        {
            Thread.Sleep(sleep);
        }
        return int.Parse(Get(release, "STUB_EXIT_CODE", "0"));
    }

    private static int PlayJavac(string exe)
    {
        Console.Out.WriteLine("javac " + Get(ReadRelease(exe), "JAVA_VERSION", "0"));
        return 0;
    }

    // Stands in for powershell.exe when a test exercises FTC-Sim.cmd: it records its arguments, "starts" the script by
    // deleting the marker file unless told not to, and exits with the requested code.
    private static int PlayPowerShell(string[] args)
    {
        string log = Environment.GetEnvironmentVariable("STUB_PS_LOG");
        if (!string.IsNullOrEmpty(log))
        {
            File.WriteAllLines(log, args);
        }
        string marker = Environment.GetEnvironmentVariable("FTCSIM_STAGE0_MARK");
        if (Environment.GetEnvironmentVariable("STUB_PS_KEEP_MARK") != "1" && !string.IsNullOrEmpty(marker) && File.Exists(marker))
        {
            File.Delete(marker);
        }
        return int.Parse(Environment.GetEnvironmentVariable("STUB_PS_EXIT") ?? "0");
    }
}
