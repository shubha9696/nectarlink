using System;
using System.IO;
using System.Threading;
using System.Runtime.InteropServices;

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

        [DllImport("user32.dll")]
        static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);

        [DllImport("user32.dll")]
        static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        const uint MOUSEEVENTF_MOVE = 0x0001;
        const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        const uint MOUSEEVENTF_LEFTUP = 0x0004;
        const uint MOUSEEVENTF_ABSOLUTE = 0x8000;

        [StructLayout(LayoutKind.Sequential)]
        struct INPUT
        {
            public uint type;
            public InputUnion u;
        }

        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public UIntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public UIntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct HARDWAREINPUT
        {
            public uint uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        [DllImport("user32.dll", SetLastError = true)]
        static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        const uint INPUT_KEYBOARD = 1;
        const uint KEYEVENTF_KEYUP = 0x0002;
        const uint KEYEVENTF_SCANCODE = 0x0008;

        static void Log(string msg)
        {
            try
            {
                File.AppendAllText(@"C:\nectarlink\unlock.log", string.Format("[{0:yyyy-MM-dd HH:mm:ss.fff}] {1}\r\n", DateTime.Now, msg));
            }
            catch { }
        }

        static void SendKey(ushort vk, ushort scan)
        {
            keybd_event((byte)vk, (byte)scan, 0, UIntPtr.Zero);
            Thread.Sleep(40);
            keybd_event((byte)vk, (byte)scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
            Thread.Sleep(35);
        }

        static void ClickPinBox()
        {
            // Move cursor to center-screen where Windows 11 displays the PIN password box (x=50%, y=51%)
            mouse_event(MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_MOVE, 32768, 33400, 0, UIntPtr.Zero);
            Thread.Sleep(30);
            mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(40);
            mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
            Thread.Sleep(80);
        }

        static void AttachInputDesktop()
        {
            try
            {
                IntPtr hwinsta = OpenWindowStationW("WinSta0", false, 0x037F);
                if (hwinsta != IntPtr.Zero)
                {
                    SetProcessWindowStation(hwinsta);
                    Log("SetProcessWindowStation WinSta0 succeeded");
                }
            }
            catch (Exception ex)
            {
                Log("WindowStation attach exception: " + ex.Message);
            }

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
                            Log(string.Format("SetThreadDesktop succeeded with access 0x{0:X}", access));
                            return;
                        }
                    }
                }
                Thread.Sleep(100);
            }
            Log("Note: SetThreadDesktop fallback exhausted, using global hardware input");
        }

        static void TypePin(string pin)
        {
            // Focus PIN box directly by clicking it
            ClickPinBox();
            Thread.Sleep(80);

            // Clear any stray characters using 8 Backspaces
            for (int i = 0; i < 8; i++)
            {
                SendKey(0x08, 0x0E); // VK_BACK
            }
            Thread.Sleep(100);

            // Type PIN digits with VK and scan codes
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

            // Submit PIN with Enter
            SendKey(0x0D, 0x1C); // VK_RETURN
        }

        static void Main(string[] args)
        {
            Log("=== NectarLink 64-bit unlock helper started ===");
            string pin = "";
            if (args.Length > 0 && !string.IsNullOrWhiteSpace(args[0]))
            {
                pin = args[0].Trim();
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
            Log(string.Format("Using PIN length: {0}", pin.Length));

            // Attach to interactive desktop
            AttachInputDesktop();

            // Stage 1: Wake display and dismiss lock curtain
            Log("Stage 1: Waking display and dismissing lock curtain...");
            SendKey(0x20, 0x39); // VK_SPACE
            Thread.Sleep(80);
            SendKey(0x0D, 0x1C); // VK_RETURN
            Thread.Sleep(1400); // Allow lock curtain slide-up animation to complete
            AttachInputDesktop();

            // Stage 2: First PIN entry attempt
            Log("Stage 2: Entering PIN...");
            TypePin(pin);

            // Wait 1200ms and check if screen unlocked; if still locked, retry once
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
            Log("=== Unlock helper finished ===");
        }
    }
}
