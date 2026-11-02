using System;
using System.IO;
using System.IO.Pipes;
using System.Threading;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Win32;
using System.Diagnostics;

namespace NectarLinkUnlockHelper
{
    class Program
    {
        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr OpenWindowStationW([MarshalAs(UnmanagedType.LPWStr)] string lpszWinSta, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetProcessWindowStation(IntPtr hWinSta);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetThreadDesktop(IntPtr hDesktop);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool CloseDesktop(IntPtr hDesktop);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool GetUserObjectInformation(IntPtr hObj, int nIndex, [Out] byte[] pvInfo, uint nLength, out uint lpnLengthNeeded);

        [DllImport("user32.dll")]
        static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        const uint MOUSEEVENTF_MOVE = 0x0001;
        const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        const uint MOUSEEVENTF_LEFTUP = 0x0004;
        const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

        const uint KEYEVENTF_KEYUP = 0x0002;

        static void Log(string msg)
        {
            try
            {
                File.AppendAllText(@"C:\nectarlink\unlock.log", string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] {1}\r\n", DateTime.Now, msg));
            }
            catch { }
        }

        static bool IsWorkstationLocked()
        {
            try
            {
                IntPtr hdesk = OpenInputDesktop(0, false, 0x0001);
                if (hdesk == IntPtr.Zero)
                {
                    return true;
                }
                byte[] buf = new byte[256];
                uint needed;
                if (GetUserObjectInformation(hdesk, 2, buf, (uint)buf.Length, out needed))
                {
                    string name = System.Text.Encoding.ASCII.GetString(buf, 0, (int)needed).TrimEnd('\0');
                    CloseDesktop(hdesk);
                    return !name.Equals("Default", StringComparison.OrdinalIgnoreCase);
                }
                CloseDesktop(hdesk);
            }
            catch { }
            return false;
        }

        static void AttachInputDesktop()
        {
            try
            {
                IntPtr hwinsta = OpenWindowStationW("WinSta0", false, 0x037F);
                if (hwinsta != IntPtr.Zero)
                {
                    SetProcessWindowStation(hwinsta);
                }
            }
            catch { }

            uint[] accessRights = new uint[] { 0x01FF, 0x0081, 0x02000000, 0x0001, 0x0080 };
            for (int attempt = 0; attempt < 5; attempt++)
            {
                foreach (uint access in accessRights)
                {
                    IntPtr hdesk = OpenInputDesktop(0, false, access);
                    if (hdesk != IntPtr.Zero)
                    {
                        if (SetThreadDesktop(hdesk))
                        {
                            return;
                        }
                    }
                }
                Thread.Sleep(50);
            }
        }

        static void SendKey(ushort vk, ushort scan)
        {
            keybd_event((byte)vk, (byte)scan, 0, UIntPtr.Zero);
            Thread.Sleep(35);
            keybd_event((byte)vk, (byte)scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
            Thread.Sleep(30);
        }

        static void ClickPinBox()
        {
            mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, 32768, 33400, 0, UIntPtr.Zero);
            Thread.Sleep(30);
            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(40);
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(80);
        }

        static void TypePin(string pin)
        {
            ClickPinBox();
            Thread.Sleep(80);

            for (int i = 0; i < 8; i++)
            {
                SendKey(0x08, 0x0E); // VK_BACK
            }
            Thread.Sleep(100);

            foreach (char c in pin)
            {
                ushort vk = 0;
                ushort scan = 0;
                switch (c)
                {
                    case '1': vk = 0x31; scan = 0x02; break;
                    case '2': vk = 0x32; scan = 0x03; break;
                    case '3': vk = 0x33; scan = 0x04; break;
                    case '4': vk = 0x34; scan = 0x05; break;
                    case '5': vk = 0x35; scan = 0x06; break;
                    case '6': vk = 0x36; scan = 0x07; break;
                    case '7': vk = 0x37; scan = 0x08; break;
                    case '8': vk = 0x38; scan = 0x09; break;
                    case '9': vk = 0x39; scan = 0x0A; break;
                    case '0': vk = 0x30; scan = 0x0B; break;
                }
                if (vk != 0)
                {
                    SendKey(vk, scan);
                }
            }
            Thread.Sleep(150);
            SendKey(0x0D, 0x1C); // VK_RETURN
        }

        static void ExecuteUnlock(string pin)
        {
            Log("ExecuteUnlock: starting unlock sequence with PIN len " + pin.Length);
            AttachInputDesktop();

            Log("Stage 1: Waking display and dismissing lock curtain...");
            SendKey(0x20, 0x39); // VK_SPACE
            Thread.Sleep(80);
            SendKey(0x0D, 0x1C); // VK_RETURN
            Thread.Sleep(1200);
            AttachInputDesktop();

            Log("Stage 2: Entering PIN...");
            TypePin(pin);

            Thread.Sleep(1200);
            IntPtr checkDesk = OpenInputDesktop(0, false, 0x0001);
            if (checkDesk == IntPtr.Zero)
            {
                Log("Stage 3: Screen still locked, executing retry PIN entry...");
                AttachInputDesktop();
                TypePin(pin);
            }
            else
            {
                Log("Workstation unlocked successfully!");
                CloseDesktop(checkDesk);
            }
        }

        static void CleanRegistryDuplicates()
        {
            try
            {
                Registry.LocalMachine.DeleteSubKeyTree(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\{2450BA1D-5E26-4907-8488-C9AB2F664DFC}", false);
            }
            catch { }
            try
            {
                Registry.LocalMachine.DeleteSubKeyTree(@"Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\{2450BA1D-5E26-4907-8488-C9AB2F664DFC}", false);
            }
            catch { }
        }

        static void PerformCleanInstall()
        {
            Log("=== Performing clean install of latest NectarLink as SYSTEM ===");
            try
            {
                foreach (var p in Process.GetProcesses())
                {
                    try
                    {
                        string name = p.ProcessName.ToLower();
                        if (name.Contains("nectarlink") || name.Contains("smartconnect"))
                        {
                            Log("Killing process: " + p.ProcessName + " (" + p.Id + ")");
                            p.Kill();
                            p.WaitForExit(3000);
                        }
                    }
                    catch { }
                }

                CleanRegistryDuplicates();

                try
                {
                    Registry.LocalMachine.DeleteSubKeyTree(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\Nectarlink", false);
                }
                catch { }

                string setupPath = @"C:\nectarlink\Nectarlink-Custom-Windows-Setup.exe";
                if (File.Exists(setupPath))
                {
                    Log("Launching installer: " + setupPath + " /S");
                    var psi = new ProcessStartInfo(setupPath, "/S")
                    {
                        UseShellExecute = false,
                        CreateNoWindow = true
                    };
                    var proc = Process.Start(psi);
                    if (proc != null)
                    {
                        proc.WaitForExit();
                        Log("Installer exited with code: " + proc.ExitCode);
                    }
                }
            }
            catch (Exception ex)
            {
                Log("PerformCleanInstall error: " + ex.ToString());
            }
            finally
            {
                try { File.Delete(@"C:\nectarlink\do_clean_install.flag"); } catch { }
            }
            Log("=== Clean install finished ===");
        }

        static void HandleCommand(string line)
        {
            if (string.IsNullOrWhiteSpace(line)) return;
            string[] parts = line.Trim().Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return;

            string cmd = parts[0].ToUpperInvariant();
            switch (cmd)
            {
                case "PING":
                    break;

                case "UNLOCK":
                    string pin = parts.Length > 1 ? parts[1] : "9500";
                    ExecuteUnlock(pin);
                    break;

                case "TOUCH":
                    // TOUCH down 0.5 0.5
                    if (parts.Length >= 4)
                    {
                        string action = parts[1].ToLowerInvariant();
                        float fx = 0f, fy = 0f;
                        float.TryParse(parts[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out fx);
                        float.TryParse(parts[3], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out fy);
                        uint ax = (uint)Math.Round(Math.Max(0f, Math.Min(1f, fx)) * 65535f);
                        uint ay = (uint)Math.Round(Math.Max(0f, Math.Min(1f, fy)) * 65535f);

                        AttachInputDesktop();
                        mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, ax, ay, 0, UIntPtr.Zero);
                        if (action == "down")
                        {
                            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                        }
                        else if (action == "up")
                        {
                            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                        }
                    }
                    break;

                case "MOUSE":
                    // MOUSE <flags> <dx> <dy> <data>
                    if (parts.Length >= 5)
                    {
                        uint flags = 0, dx = 0, dy = 0, data = 0;
                        uint.TryParse(parts[1], out flags);
                        uint.TryParse(parts[2], out dx);
                        uint.TryParse(parts[3], out dy);
                        uint.TryParse(parts[4], out data);

                        AttachInputDesktop();
                        mouse_event(flags, dx, dy, data, UIntPtr.Zero);
                    }
                    break;

                case "KEY":
                    // KEY <vk> <scan> <flags>
                    if (parts.Length >= 4)
                    {
                        byte vk = 0, scan = 0;
                        uint flags = 0;
                        byte.TryParse(parts[1], out vk);
                        byte.TryParse(parts[2], out scan);
                        uint.TryParse(parts[3], out flags);

                        AttachInputDesktop();
                        keybd_event(vk, scan, flags, UIntPtr.Zero);
                    }
                    break;

                case "TYPE":
                    if (parts.Length > 1)
                    {
                        string text = line.Substring(line.IndexOf(' ') + 1);
                        AttachInputDesktop();
                        foreach (char c in text)
                        {
                            if (c >= '0' && c <= '9')
                            {
                                byte vk = (byte)(0x30 + (c - '0'));
                                keybd_event(vk, 0, 0, UIntPtr.Zero);
                                Thread.Sleep(20);
                                keybd_event(vk, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
                                Thread.Sleep(20);
                            }
                        }
                    }
                    break;
            }
        }

        static void RunServer()
        {
            Log("=== NectarLink Input Service Pipe Server started ===");
            CleanRegistryDuplicates();

            PipeSecurity ps = new PipeSecurity();
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));

            while (true)
            {
                try
                {
                    using (var pipe = new NamedPipeServerStream(
                        "nectarlink_lock_input",
                        PipeDirection.InOut,
                        10,
                        PipeTransmissionMode.Byte,
                        PipeOptions.Asynchronous,
                        4096,
                        4096,
                        ps))
                    {
                        pipe.WaitForConnection();
                        using (var reader = new StreamReader(pipe))
                        using (var writer = new StreamWriter(pipe) { AutoFlush = true })
                        {
                            string line;
                            while ((line = reader.ReadLine()) != null)
                            {
                                if (line.Trim().Equals("PING", StringComparison.OrdinalIgnoreCase))
                                {
                                    writer.WriteLine("PONG");
                                }
                                else
                                {
                                    HandleCommand(line);
                                    writer.WriteLine("OK");
                                }
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log("Pipe Server exception: " + ex.Message);
                    Thread.Sleep(200);
                }
            }
        }

        static void Main(string[] args)
        {
            CleanRegistryDuplicates();

            if (File.Exists(@"C:\nectarlink\do_clean_install.flag"))
            {
                PerformCleanInstall();
                return;
            }

            bool isService = false;
            string pin = null;

            foreach (var arg in args)
            {
                if (arg.Equals("--service", StringComparison.OrdinalIgnoreCase) ||
                    arg.Equals("-s", StringComparison.OrdinalIgnoreCase))
                {
                    isService = true;
                }
                else if (arg.Equals("--clean-install", StringComparison.OrdinalIgnoreCase))
                {
                    PerformCleanInstall();
                    return;
                }
                else if (!arg.StartsWith("-"))
                {
                    pin = arg.Trim();
                }
            }

            // If args contain --service or no args provided while running non-interactively, run the pipe server!
            if (isService || args.Length == 0)
            {
                RunServer();
                return;
            }

            // Otherwise, one-shot PIN unlock mode
            Log("=== NectarLink one-shot unlock started ===");
            if (!IsWorkstationLocked())
            {
                Log("Workstation is already unlocked; skipping.");
                return;
            }

            if (string.IsNullOrEmpty(pin))
            {
                string[] candidates = new string[] {
                    @"C:\nectarlink\saved_pin.txt",
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"nectarlink\saved_pin.txt"),
                    Path.Combine(Path.GetTempPath(), "nectarlink_saved_pin.txt")
                };
                foreach (var path in candidates)
                {
                    if (File.Exists(path))
                    {
                        try {
                            string content = File.ReadAllText(path).Trim();
                            if (!string.IsNullOrEmpty(content)) { pin = content; break; }
                        } catch { }
                    }
                }
            }
            if (string.IsNullOrEmpty(pin))
            {
                pin = "9500";
            }

            ExecuteUnlock(pin);
        }
    }
}
