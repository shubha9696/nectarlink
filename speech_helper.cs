using System;
using System.IO;
using System.Speech.Recognition;
using System.Text;

class Program {
    static void Main(string[] args) {
        if (args.Length == 0) {
            Console.WriteLine("Usage: speech_helper.exe <wav-path>");
            return;
        }
        string wavPath = args[0];
        if (!File.Exists(wavPath)) {
            Console.WriteLine("File not found: " + wavPath);
            return;
        }
        try {
            using (var sre = new SpeechRecognitionEngine()) {
                sre.LoadGrammar(new DictationGrammar());
                sre.SetInputToWaveFile(wavPath);
                
                var sb = new StringBuilder();
                while (true) {
                    var result = sre.Recognize();
                    if (result == null) break;
                    if (!string.IsNullOrEmpty(result.Text)) {
                        if (sb.Length > 0) sb.Append(" ");
                        sb.Append(result.Text);
                    }
                }
                
                string transcribed = sb.ToString().Trim();
                if (!string.IsNullOrEmpty(transcribed)) {
                    Console.WriteLine("RECOGNIZED:" + transcribed);
                } else {
                    Console.WriteLine("NO_SPEECH");
                }
            }
        } catch (Exception ex) {
            Console.WriteLine("ERROR:" + ex.Message);
        }
    }
}
