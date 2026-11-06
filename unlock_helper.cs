using System;
using System.IO;
using System.IO.Pipes;
using System.Threading;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Win32;
using System.Diagnostics;
using System.Reflection;
using System.Linq;

namespace NectarLinkUnlockHelper
{
    class Program
    {
        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern uint WTSGetActiveConsoleSessionId();

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSQueryUserToken(uint SessionId, out IntPtr phToken);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool OpenProcessToken(IntPtr ProcessHandle, uint DesiredAccess, out IntPtr TokenHandle);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool DuplicateTokenEx(IntPtr hExistingToken, uint dwDesiredAccess, IntPtr lpTokenAttributes, int ImpersonationLevel, int TokenType, out IntPtr phNewToken);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcessAsUser(IntPtr hToken, string lpApplicationName, string lpCommandLine, IntPtr lpProcessAttributes, IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory, ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr hObject);

        [StructLayout(LayoutKind.Sequential)]
        struct STARTUPINFO
        {
            public int cb;
            public string lpReserved;
            public string lpDesktop;
            public string lpTitle;
            public int dwX;
            public int dwY;
            public int dwXSize;
            public int dwYSize;
            public int dwXCountChars;
            public int dwYCountChars;
            public int dwFillAttribute;
            public int dwFlags;
            public short wShowWindow;
            public short cbReserved2;
            public IntPtr lpReserved2;
            public IntPtr hStdInput;
            public IntPtr hStdOutput;
            public IntPtr hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_INFORMATION
        {
            public IntPtr hProcess;
            public IntPtr hThread;
            public int dwProcessId;
            public int dwThreadId;
        }

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr OpenWindowStationW([MarshalAs(UnmanagedType.LPWStr)] string lpszWinSta, bool fInherit, uint dwDesiredAccess);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool SetProcessWindowStation(IntPtr hWinSta);

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
        static IntPtr currentWinSta = IntPtr.Zero;

        static void Log(string msg)
        {
            try
            {
                File.AppendAllText(@"C:\nectarlink\unlock.log", string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] (PID:{1} S:{2}) {3}\r\n", DateTime.Now, Process.GetCurrentProcess().Id, Process.GetCurrentProcess().SessionId, msg));
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
                if (currentWinSta == IntPtr.Zero)
                {
                    currentWinSta = OpenWindowStationW("WinSta0", false, WRITE_DAC | 0x037F);
                    if (currentWinSta != IntPtr.Zero)
                    {
                        SetProcessWindowStation(currentWinSta);
                        GrantDesktopAccess(currentWinSta);
                    }
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
                    // If OpenInputDesktop failed, likely on secure desktop
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
                if (currentWinSta == IntPtr.Zero)
                {
                    currentWinSta = OpenWindowStationW("WinSta0", false, 0x037F);
                    if (currentWinSta != IntPtr.Zero)
                    {
                        SetProcessWindowStation(currentWinSta);
                    }
                }
            }
            catch { }

            IntPtr hdesk = IntPtr.Zero;
            uint[] accessRights = new uint[] { 0x01FF, 0x0081, 0x02000000, 0x0001, 0x0080 };
            foreach (uint access in accessRights)
            {
                hdesk = OpenInputDesktop(0, false, access);
                if (hdesk != IntPtr.Zero) break;
            }

            if (hdesk == IntPtr.Zero)
            {
                // Fallback to explicit Winlogon
                hdesk = OpenDesktopW("Winlogon", 0, false, 0x01FF);
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

        static void SendKey(byte vk, byte scan)
        {
            keybd_event(vk, scan, 0, UIntPtr.Zero);
            Thread.Sleep(20);
            keybd_event(vk, scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
            Thread.Sleep(20);
        }

        static void TypeChar(char c)
        {
            if (c >= '0' && c <= '9')
            {
                byte vk = (byte)(0x30 + (c - '0'));
                byte scan = (byte)(c == '0' ? 0x0B : (0x02 + (c - '1')));
                SendKey(vk, scan);
            }
            else if (c >= 'a' && c <= 'z')
            {
                byte vk = (byte)(0x41 + (c - 'a'));
                SendKey(vk, 0);
            }
            else if (c >= 'A' && c <= 'Z')
            {
                byte vk = (byte)(0x41 + (c - 'A'));
                keybd_event(0x10, 0x2A, 0, UIntPtr.Zero);
                Thread.Sleep(15);
                SendKey(vk, 0);
                Thread.Sleep(15);
                keybd_event(0x10, 0x2A, KEYEVENTF_KEYUP, UIntPtr.Zero);
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
        }

        static string ReadSavedPin()
        {
            string[] candidatePaths = new string[] {
                @"C:\nectarlink\saved_pin.txt",
                @"C:\nectarlink-app\saved_pin.txt"
            };
            foreach (var path in candidatePaths)
            {
                if (File.Exists(path))
                {
                    try
                    {
                        string content = File.ReadAllText(path).Trim();
                        if (!string.IsNullOrEmpty(content)) return content;
                    } catch { }
                }
            }
            return "9500";
        }

        static void TypePin(string pin)
        {
            if (string.IsNullOrEmpty(pin)) return;
            AttachInputDesktop();

            // Escape any existing error message or camera/lock prompt
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(80);

            // Select all (Ctrl+A) and clear with backspaces to guarantee empty field
            keybd_event(0x11, 0x1D, 0, UIntPtr.Zero); // Ctrl down
            Thread.Sleep(20);
            SendKey(0x41, 0x1E); // 'A'
            Thread.Sleep(20);
            keybd_event(0x11, 0x1D, KEYEVENTF_KEYUP, UIntPtr.Zero); // Ctrl up
            Thread.Sleep(30);

            for (int i = 0; i < 8; i++)
            {
                SendKey(0x08, 0x0E); // VK_BACK
                Thread.Sleep(15);
            }
            Thread.Sleep(60);

            foreach (char c in pin)
            {
                TypeChar(c);
                Thread.Sleep(35);
            }
            Thread.Sleep(120);
            SendKey(0x0D, 0x1C); // VK_RETURN
        }

        static void ExecuteUnlock(string pin)
        {
            if (string.IsNullOrEmpty(pin))
            {
                pin = ReadSavedPin();
            }

            Log("ExecuteUnlock: starting unlock sequence with PIN len " + pin.Length);
            AttachInputDesktop();
            EnsureDesktopsAccessible();

            string desk = GetActiveDesktopName();
            Log("Active desktop before unlock: " + desk);

            // Stage 1: Wake display and dismiss lock curtain
            Log("Stage 1: Waking display and dismissing lock curtain...");
            SendKey(0x20, 0x39); // VK_SPACE
            Thread.Sleep(100);
            SendKey(0x0D, 0x1C); // VK_RETURN
            Thread.Sleep(850);   // Wait for curtain slide-up animation to complete

            AttachInputDesktop();
            EnsureDesktopsAccessible();

            // Clear any stale error popup or Windows Hello camera prompt
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(100);

            Log("Stage 2: Entering PIN...");
            TypePin(pin);

            Thread.Sleep(1200);
            if (IsWorkstationLocked())
            {
                Log("Stage 3: Screen still locked, executing retry PIN entry...");
                AttachInputDesktop();
                EnsureDesktopsAccessible();

                SendKey(0x20, 0x39); // VK_SPACE
                Thread.Sleep(120);
                SendKey(0x1B, 0x01); // VK_ESCAPE
                Thread.Sleep(100);

                TypePin(pin);

                Thread.Sleep(1200);
                if (IsWorkstationLocked())
                {
                    Log("Workstation unlock attempt completed; still locked.");
                }
                else
                {
                    Log("Workstation unlocked successfully on retry!");
                }
            }
            else
            {
                Log("Workstation unlocked successfully!");
            }
        }

        static bool LaunchInSession(uint sessionId, string arguments)
        {
            Log("LaunchInSession: targeting session " + sessionId + " with args: " + arguments);
            IntPtr hPrimaryToken = IntPtr.Zero;

            // Strategy 1: Find winlogon.exe in target session (runs as SYSTEM directly on target session's desktop)
            try
            {
                Process[] procs = Process.GetProcessesByName("winlogon");
                foreach (var p in procs)
                {
                    if (p.SessionId == (int)sessionId)
                    {
                        IntPtr hProc = OpenProcess(0x1400 /* PROCESS_QUERY_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION */, false, p.Id);
                        if (hProc != IntPtr.Zero)
                        {
                            IntPtr hToken;
                            if (OpenProcessToken(hProc, 0x0002 | 0x0008 | 0x0001, out hToken))
                            {
                                DuplicateTokenEx(hToken, 0x10000000, IntPtr.Zero, 2, 1, out hPrimaryToken);
                                CloseHandle(hToken);
                            }
                            CloseHandle(hProc);
                            if (hPrimaryToken != IntPtr.Zero)
                            {
                                Log("LaunchInSession: successfully duplicated winlogon token for session " + sessionId);
                                break;
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log("LaunchInSession winlogon token error: " + ex.Message);
            }

            // Strategy 2: Fall back to WTSQueryUserToken
            if (hPrimaryToken == IntPtr.Zero)
            {
                try
                {
                    IntPtr hUserToken;
                    if (WTSQueryUserToken(sessionId, out hUserToken))
                    {
                        DuplicateTokenEx(hUserToken, 0x10000000, IntPtr.Zero, 2, 1, out hPrimaryToken);
                        CloseHandle(hUserToken);
                        if (hPrimaryToken != IntPtr.Zero)
                        {
                            Log("LaunchInSession: obtained token via WTSQueryUserToken for session " + sessionId);
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log("LaunchInSession WTSQueryUserToken error: " + ex.Message);
                }
            }

            if (hPrimaryToken == IntPtr.Zero)
            {
                Log("LaunchInSession: failed to obtain token for session " + sessionId);
                return false;
            }

            try
            {
                STARTUPINFO si = new STARTUPINFO();
                si.cb = Marshal.SizeOf(si);
                si.lpDesktop = @"WinSta0\Winlogon";

                PROCESS_INFORMATION pi;
                string exePath = Assembly.GetExecutingAssembly().Location;
                if (!File.Exists(exePath))
                {
                    string[] candidates = new string[] {
                        @"C:\Program Files\Nectarlink\unlock_helper.exe",
                        @"C:\nectarlink-app\unlock_helper.exe",
                        @"C:\nectarlink\unlock_helper.exe"
                    };
                    foreach (var c in candidates)
                    {
                        if (File.Exists(c)) { exePath = c; break; }
                    }
                }
                string cmdLine = "\"" + exePath + "\" " + arguments;

                bool ok = CreateProcessAsUser(hPrimaryToken, null, cmdLine, IntPtr.Zero, IntPtr.Zero, false, 0x08000000 /* CREATE_NO_WINDOW */, IntPtr.Zero, null, ref si, out pi);
                CloseHandle(hPrimaryToken);

                if (ok)
                {
                    Log("LaunchInSession: successfully spawned PID " + pi.dwProcessId + " into session " + sessionId);
                    CloseHandle(pi.hProcess);
                    CloseHandle(pi.hThread);
                    return true;
                }
                else
                {
                    Log("LaunchInSession: CreateProcessAsUser failed with error " + Marshal.GetLastWin32Error());
                }
            }
            catch (Exception ex)
            {
                Log("LaunchInSession CreateProcessAsUser exception: " + ex.Message);
                if (hPrimaryToken != IntPtr.Zero) CloseHandle(hPrimaryToken);
            }
            return false;
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

        static void HandleCommand(string line)
        {
            if (string.IsNullOrWhiteSpace(line)) return;
            string[] parts = line.Trim().Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return;

            string cmd = parts[0].ToUpperInvariant();
            int mySession = Process.GetCurrentProcess().SessionId;

            if (cmd == "QUIT" || cmd == "EXIT")
            {
                Log("Quit command received via named pipe. Terminating PID " + Process.GetCurrentProcess().Id);
                Environment.Exit(0);
                return;
            }

            switch (cmd)
            {
                case "PING":
                    break;

                case "PERM":
                    EnsureDesktopsAccessible();
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, "--perm");
                    }
                    break;

                case "UNLOCK":
                    string pin = parts.Length > 1 ? parts[1] : ReadSavedPin();
                    if (mySession == 0)
                    {
                        // In Session 0, we must launch injector into active user session!
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        Log("Session 0 received UNLOCK; spawning injector into session " + activeSession);
                        LaunchInSession(activeSession, "--inject-pin " + pin);
                    }
                    else
                    {
                        ExecuteUnlock(pin);
                    }
                    break;

                case "TOUCH":
                    // TOUCH down 0.5 0.5
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else if (parts.Length >= 4)
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
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else if (parts.Length >= 3)
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
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else if (parts.Length >= 5)
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
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else if (parts.Length >= 4)
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
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, "--enter");
                    }
                    else
                    {
                        AttachInputDesktop();
                        SendKey(0x0D, 0x1C);
                    }
                    break;

                case "BACKSPACE":
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else
                    {
                        int count = 1;
                        if (parts.Length > 1) int.TryParse(parts[1], out count);
                        AttachInputDesktop();
                        for (int i = 0; i < Math.Max(1, count); i++)
                        {
                            SendKey(0x08, 0x0E);
                            Thread.Sleep(15);
                        }
                    }
                    break;

                case "TYPE":
                    if (mySession == 0)
                    {
                        uint activeSession = WTSGetActiveConsoleSessionId();
                        LaunchInSession(activeSession, line);
                    }
                    else if (parts.Length > 1)
                    {
                        string toType = line.Substring(5);
                        AttachInputDesktop();
                        foreach (char c in toType)
                        {
                            TypeChar(c);
                            Thread.Sleep(15);
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
                    try
                    {
                        EnsureDesktopsAccessible();
                    }
                    catch { }
                    Thread.Sleep(1500);
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
                        PipeOptions.None,
                        4096,
                        4096,
                        ps))
                    {
                        Log("Pipe server waiting for connection on nectarlink_lock_input...");
                        pipe.WaitForConnection();
                        Log("Pipe client connected successfully!");
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
                    // Crucial: sleep 2000ms so we do not spam CPU if another instance holds the pipe!
                    Thread.Sleep(2000);
                }
            }
        }

        static void Main(string[] args)
        {
            try
            {
                int mySession = Process.GetCurrentProcess().SessionId;
                Log("Main started with args: " + (args != null ? string.Join(" ", args) : "null"));
                CleanRegistryDuplicates();

                if (args != null && args.Length > 0)
                {
                    for (int i = 0; i < args.Length; i++)
                    {
                        string a = args[i];
                        if (a.Equals("--inject-pin", StringComparison.OrdinalIgnoreCase) || a.Equals("-i", StringComparison.OrdinalIgnoreCase))
                        {
                            string pin = (i + 1 < args.Length) ? args[i + 1] : ReadSavedPin();
                            Log("=== Session " + mySession + " PIN Injector Mode (pin len: " + pin.Length + ") ===");
                            ExecuteUnlock(pin);
                            return;
                        }
                        else if (a.Equals("--perm", StringComparison.OrdinalIgnoreCase))
                        {
                            EnsureDesktopsAccessible();
                            return;
                        }
                        else if (a.Equals("--enter", StringComparison.OrdinalIgnoreCase))
                        {
                            AttachInputDesktop();
                            SendKey(0x0D, 0x1C);
                            return;
                        }
                        else if (a.Equals("KEY", StringComparison.OrdinalIgnoreCase) ||
                                 a.Equals("TOUCH", StringComparison.OrdinalIgnoreCase) ||
                                 a.Equals("CLICK", StringComparison.OrdinalIgnoreCase) ||
                                 a.Equals("MOUSE", StringComparison.OrdinalIgnoreCase) ||
                                 a.Equals("TYPE", StringComparison.OrdinalIgnoreCase))
                        {
                            HandleCommand(string.Join(" ", args));
                            return;
                        }
                    }
                }

                // If args contain --service or running as service/task:
                RunServer();
            }
            catch (Exception ex)
            {
                Log("Fatal exception in Main: " + ex.ToString());
            }
        }
    }
}
