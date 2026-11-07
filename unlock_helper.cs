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
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern uint WTSGetActiveConsoleSessionId();

        static uint GetActiveConsoleSession()
        {
            try
            {
                uint s = WTSGetActiveConsoleSessionId();
                if (s != 0xFFFFFFFF) return s;
            }
            catch { }
            return 1;
        }

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSQueryUserToken(uint SessionId, out IntPtr phToken);

        [DllImport("userenv.dll", SetLastError = true)]
        static extern bool CreateEnvironmentBlock(out IntPtr lpEnvironment, IntPtr hToken, bool bInherit);

        [DllImport("userenv.dll", SetLastError = true)]
        static extern bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool OpenProcessToken(IntPtr ProcessHandle, uint DesiredAccess, out IntPtr TokenHandle);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool DuplicateTokenEx(IntPtr hExistingToken, uint dwDesiredAccess, IntPtr lpTokenAttributes, int ImpersonationLevel, int TokenType, out IntPtr phNewToken);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcessWithTokenW(IntPtr hToken, uint dwLogonFlags, string lpApplicationName, string lpCommandLine, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory, ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

        [StructLayout(LayoutKind.Sequential)]
        struct LUID { public uint LowPart; public int HighPart; }

        [StructLayout(LayoutKind.Sequential)]
        struct TOKEN_PRIVILEGES { public uint PrivilegeCount; public LUID Luid; public uint Attributes; }

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool AdjustTokenPrivileges(IntPtr TokenHandle, bool DisableAllPrivileges, ref TOKEN_PRIVILEGES NewState, uint BufferLengthInBytes, IntPtr PreviousState, IntPtr ReturnLengthInBytes);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Auto)]
        static extern bool LookupPrivilegeValue(string lpSystemName, string lpName, out LUID lpLuid);

        static void EnablePrivilege(string privName)
        {
            try
            {
                IntPtr hToken;
                if (OpenProcessToken(Process.GetCurrentProcess().Handle, 0x0020 | 0x0008, out hToken))
                {
                    LUID luid;
                    if (LookupPrivilegeValue(null, privName, out luid))
                    {
                        TOKEN_PRIVILEGES tp = new TOKEN_PRIVILEGES();
                        tp.PrivilegeCount = 1;
                        tp.Luid = luid;
                        tp.Attributes = 0x00000002;
                        AdjustTokenPrivileges(hToken, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero);
                    }
                    CloseHandle(hToken);
                }
            }
            catch { }
        }

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
            for (int retry = 0; retry < 5; retry++)
            {
                try
                {
                    using (var fs = new FileStream(@"C:\nectarlink\unlock.log", FileMode.Append, FileAccess.Write, FileShare.ReadWrite))
                    using (var sw = new StreamWriter(fs))
                    {
                        sw.WriteLine(string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] (PID:{1} S:{2}) {3}", DateTime.Now, Process.GetCurrentProcess().Id, Process.GetCurrentProcess().SessionId, msg));
                    }
                    break;
                }
                catch { Thread.Sleep(15); }
            }
        }

        static void GrantDesktopAccess(IntPtr handle)
        {
            if (handle == IntPtr.Zero) return;
            try
            {
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
            Thread.Sleep(15);
            keybd_event(vk, scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
            Thread.Sleep(15);
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
                @"C:\Program Files\Nectarlink\saved_pin.txt",
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

        static void SyncToProgramFiles()
        {
            try
            {
                string srcExe = Assembly.GetExecutingAssembly().Location;
                string pfDir = @"C:\Program Files\Nectarlink";
                string dstExe = Path.Combine(pfDir, "unlock_helper.exe");

                if (!Directory.Exists(pfDir))
                {
                    Directory.CreateDirectory(pfDir);
                }

                if (!string.Equals(srcExe, dstExe, StringComparison.OrdinalIgnoreCase))
                {
                    File.Copy(srcExe, dstExe, true);
                    Log("SyncToProgramFiles: copied updated binary to " + dstExe);
                }

                string srcPin = @"C:\nectarlink\saved_pin.txt";
                string dstPin = Path.Combine(pfDir, "saved_pin.txt");
                if (File.Exists(srcPin))
                {
                    File.Copy(srcPin, dstPin, true);
                    Log("SyncToProgramFiles: copied saved_pin.txt to " + dstPin);
                }
            }
            catch (Exception ex)
            {
                Log("SyncToProgramFiles error: " + ex.Message);
            }
        }

        static void ClickNormalized(uint nx, uint ny)
        {
            mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, nx, ny, 0, UIntPtr.Zero);
            Thread.Sleep(15);
            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(25);
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
        }

        static void TypePinFast(string pin)
        {
            // Focus PIN text box: X=50% (32768), Y=41.5% (27197)
            // Notice: Y=41.5% is well ABOVE 'I forgot my PIN' (at Y=46.5%), so it can NEVER mis-click!
            ClickNormalized(32768, 27197);
            Thread.Sleep(30);

            // Clear any text: Ctrl+A + Backspace
            keybd_event(0x11, 0x1D, 0, UIntPtr.Zero); // Ctrl down
            Thread.Sleep(15);
            SendKey(0x41, 0x1E); // 'A'
            Thread.Sleep(15);
            keybd_event(0x11, 0x1D, KEYEVENTF_KEYUP, UIntPtr.Zero); // Ctrl up
            Thread.Sleep(20);

            for (int i = 0; i < 6; i++)
            {
                SendKey(0x08, 0x0E); // Backspace
                Thread.Sleep(10);
            }
            Thread.Sleep(25);

            // Type digits with 20ms delay (fast & responsive)
            foreach (char c in pin)
            {
                TypeChar(c);
                Thread.Sleep(20);
            }
            Thread.Sleep(40);

            // Submit
            SendKey(0x0D, 0x1C); // Enter
        }

        static bool WaitForUnlock(int maxMs)
        {
            int iterations = Math.Max(1, maxMs / 80);
            for (int i = 0; i < iterations; i++)
            {
                Thread.Sleep(80);
                if (!IsWorkstationLocked())
                {
                    Log("WaitForUnlock: Workstation unlocked successfully!");
                    return true;
                }
            }
            return false;
        }

        static void ExecuteUnlock(string pin)
        {
            if (string.IsNullOrEmpty(pin))
            {
                pin = ReadSavedPin();
            }

            Log("ExecuteUnlock: starting fast intelligent unlock (pin len: " + pin.Length + ")");
            AttachInputDesktop();
            EnsureDesktopsAccessible();

            if (!IsWorkstationLocked())
            {
                Log("ExecuteUnlock: Workstation is already unlocked; aborting.");
                return;
            }

            // Dismiss any previously stuck modal dialogs (such as 'Verify your email' or error banners)
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(50);

            // Step 1: Wake display and dismiss lock curtain smoothly via keyboard
            Log("ExecuteUnlock: Waking display and dismissing lock curtain...");
            SendKey(0x20, 0x39); // Space
            Thread.Sleep(25);
            SendKey(0x26, 0x48); // Up Arrow
            Thread.Sleep(25);
            SendKey(0x0D, 0x1C); // Enter
            Thread.Sleep(300);

            AttachInputDesktop();
            if (!IsWorkstationLocked())
            {
                Log("ExecuteUnlock: Unlocked via Face Unlock during curtain lift; done.");
                return;
            }

            // Step 2: Intelligent Direct PIN Entry
            // After restart, or if PIN was already showing, or face recognition failed ("Couldn't recognize you."),
            // the PIN input box is ALREADY OPEN at X=50% (32768), Y=41.5% (27197).
            // NOTE: Y=41.5% is well ABOVE 'I forgot my PIN' (at Y=46.5%), so clicking here can NEVER trigger account recovery!
            Log("ExecuteUnlock: Attempting direct PIN entry into PIN box...");
            TypePinFast(pin);

            if (WaitForUnlock(650))
            {
                Log("ExecuteUnlock: Workstation successfully unlocked directly with PIN!");
                return;
            }

            // Step 3: If still locked, Face Unlock was waiting (camera icon) and PIN box wasn't open yet.
            // In Face Unlock mode, 'Sign-in options' is at X=50% (32768), Y=45.5% (29818).
            Log("ExecuteUnlock: Direct PIN did not unlock; switching from Face Unlock to PIN...");
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(50);
            ClickNormalized(32768, 29818); // Click 'Sign-in options'
            Thread.Sleep(100);

            // Select PIN tile (X=52.5% = 34406, Y=49% = 32112) + keyboard Right Arrow + Space
            ClickNormalized(34406, 32112);
            SendKey(0x27, 0x4D); // Right Arrow
            Thread.Sleep(20);
            SendKey(0x20, 0x39); // Space
            Thread.Sleep(120);

            // Now PIN box is guaranteed to be open; type PIN
            Log("ExecuteUnlock: Typing PIN after switching credential provider...");
            TypePinFast(pin);

            if (WaitForUnlock(1200))
            {
                Log("ExecuteUnlock: Workstation successfully unlocked after provider switch!");
                return;
            }

            // Step 4: Final fast retry if still locked
            Log("ExecuteUnlock: Still locked; executing final fast retry...");
            SendKey(0x1B, 0x01); // VK_ESCAPE
            Thread.Sleep(40);
            SendKey(0x20, 0x39); // Space
            Thread.Sleep(40);
            TypePinFast(pin);
            WaitForUnlock(1000);
        }

        static bool LaunchInSession(uint sessionId, string arguments)
        {
            Log("LaunchInSession: targeting session " + sessionId + " with args: " + arguments);
            EnablePrivilege("SeDebugPrivilege");
            EnablePrivilege("SeAssignPrimaryTokenPrivilege");
            EnablePrivilege("SeIncreaseQuotaPrivilege");
            EnablePrivilege("SeTcbPrivilege");
            EnablePrivilege("SeImpersonatePrivilege");

            IntPtr hPrimaryToken = IntPtr.Zero;

            // Strategy 1: Find winlogon.exe in target session (runs as SYSTEM directly on target session's desktop)
            try
            {
                Process[] procs = Process.GetProcessesByName("winlogon");
                foreach (var p in procs)
                {
                    if (p.SessionId == (int)sessionId)
                    {
                        IntPtr hProc = OpenProcess(0x1FFFFF /* PROCESS_ALL_ACCESS */, false, p.Id);
                        if (hProc != IntPtr.Zero)
                        {
                            IntPtr hToken;
                            if (OpenProcessToken(hProc, 0x000F01FF /* TOKEN_ALL_ACCESS */, out hToken))
                            {
                                DuplicateTokenEx(hToken, 0x000F01FF, IntPtr.Zero, 2, 1, out hPrimaryToken);
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
                        DuplicateTokenEx(hUserToken, 0x000F01FF, IntPtr.Zero, 2, 1, out hPrimaryToken);
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
                si.lpDesktop = null;
                si.dwFlags = 1; // STARTF_USESHOWWINDOW
                si.wShowWindow = 0; // SW_HIDE

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

                IntPtr lpEnv = IntPtr.Zero;
                uint creationFlags = 0x08000000 /* CREATE_NO_WINDOW */;
                try
                {
                    bool envOk = CreateEnvironmentBlock(out lpEnv, hPrimaryToken, false);
                    if (envOk && lpEnv != IntPtr.Zero)
                    {
                        creationFlags |= 0x00000400 /* CREATE_UNICODE_ENVIRONMENT */;
                    }
                }
                catch (Exception ex)
                {
                    Log("LaunchInSession: CreateEnvironmentBlock ex=" + ex.Message);
                }

                bool ok = CreateProcessAsUser(hPrimaryToken, null, cmdLine, IntPtr.Zero, IntPtr.Zero, false, creationFlags, lpEnv, @"C:\nectarlink", ref si, out pi);
                if (!ok)
                {
                    int err = Marshal.GetLastWin32Error();
                    Log("LaunchInSession: CreateProcessAsUser failed with error " + err + ". Trying CreateProcessWithTokenW...");
                    ok = CreateProcessWithTokenW(hPrimaryToken, 0, null, cmdLine, creationFlags, lpEnv, @"C:\nectarlink", ref si, out pi);
                    if (!ok)
                    {
                        Log("LaunchInSession: CreateProcessWithTokenW failed with error " + Marshal.GetLastWin32Error());
                    }
                }

                if (lpEnv != IntPtr.Zero)
                {
                    try { DestroyEnvironmentBlock(lpEnv); } catch { }
                }
                CloseHandle(hPrimaryToken);

                if (ok)
                {
                    Log("LaunchInSession: successfully spawned PID " + pi.dwProcessId + " into session " + sessionId);
                    WaitForSingleObject(pi.hProcess, 15000);
                    uint exitCode = 0;
                    GetExitCodeProcess(pi.hProcess, out exitCode);
                    Log("LaunchInSession: PID " + pi.dwProcessId + " exited with code 0x" + exitCode.ToString("X"));
                    CloseHandle(pi.hProcess);
                    CloseHandle(pi.hThread);
                    return true;
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

                case "SYNC":
                    SyncToProgramFiles();
                    break;

                case "INSTALL":
                    try
                    {
                        Log("INSTALL command received. Running silent installer as SYSTEM...");
                        foreach (var p in Process.GetProcessesByName("nectarlink-desktop"))
                        {
                            try { p.Kill(); p.WaitForExit(3000); } catch { }
                        }
                        string setupExe = @"C:\nectarlink\Nectarlink-v0.1.51-desktop-setup.exe";
                        if (File.Exists(setupExe))
                        {
                            var psi = new ProcessStartInfo(setupExe, "/S")
                            {
                                UseShellExecute = false,
                                CreateNoWindow = true
                            };
                            var proc = Process.Start(psi);
                            if (proc != null)
                            {
                                proc.WaitForExit(60000);
                                Log("Installer completed with exit code: " + proc.ExitCode);
                            }
                            SyncToProgramFiles();
                        }
                        else
                        {
                            Log("Installer not found: " + setupExe);
                        }
                    }
                    catch (Exception ex)
                    {
                        Log("INSTALL command failed: " + ex.Message);
                    }
                    break;

                case "PERM":
                    EnsureDesktopsAccessible();
                    if (mySession == 0)
                    {
                        uint activeSession = GetActiveConsoleSession();
                        LaunchInSession(activeSession, "--perm");
                    }
                    break;

                case "UNLOCK":
                    string pin = parts.Length > 1 ? parts[1] : ReadSavedPin();
                    if (mySession == 0)
                    {
                        uint activeSession = GetActiveConsoleSession();
                        Log("Session 0 received UNLOCK; spawning injector into session " + activeSession);
                        LaunchInSession(activeSession, "--inject-pin " + pin);
                    }
                    else
                    {
                        ExecuteUnlock(pin);
                    }
                    break;

                case "TOUCH":
                    if (mySession == 0)
                    {
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
                        uint activeSession = GetActiveConsoleSession();
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
            SyncToProgramFiles();

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
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));
            ps.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));

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
                            // Client disconnected cleanly
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log("Pipe Server exception: " + ex.Message);
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

                // The pipe server MUST ONLY run in Session 0 as SYSTEM.
                if (mySession != 0)
                {
                    Log("unlock_helper: process is in user session " + mySession + "; pipe server must only run as SYSTEM in session 0. Exiting.");
                    return;
                }

                // Global Mutex to guarantee only one service pipe server runs in Session 0
                bool createdNew;
                using (var mutex = new Mutex(true, @"Global\NectarLinkUnlockServiceMutex", out createdNew))
                {
                    if (!createdNew)
                    {
                        Log("Another instance of unlock_helper pipe service is already running. Exiting PID " + Process.GetCurrentProcess().Id);
                        return;
                    }

                    RunServer();
                }
            }
            catch (Exception ex)
            {
                Log("Fatal exception in Main: " + ex.ToString());
            }
        }
    }
}
