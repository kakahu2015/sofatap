using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using SofaTap.Desktop;

internal static class LocalizationChecks
{
    // The Chinese table is embedded under this logical name by every project
    // that compiles the Windows frontend. Parsing it here independently of
    // Strings.Load() keeps duplicates and empty values loudly visible.
    private const string ResourceName = "Sofatap.Localization.Strings.zh.json";
    private static readonly string[] FrontendSources = ["PairingWindow.cs", "MintCalmControls.cs", "Program.cs"];
    private static readonly Regex LookupPattern = new(@"Strings\s*\.\s*(?:Get|Format)\s*\(\s*""((?:[^""\\]|\\.)*)""", RegexOptions.Compiled);
    private static readonly Regex PlaceholderPattern = new(@"\{(\d+)(?:[^{}]*)\}", RegexOptions.Compiled);

    internal static int Run()
    {
        var count = 0;
        void Check(bool condition, string description) { if (!condition) throw new Exception(description); count++; }

        // (a) The embedded Chinese table loads as a JSON object with at least
        // the expected coverage, no duplicate keys and no empty translations.
        string json;
        using (var stream = typeof(Strings).Assembly.GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException("The embedded Chinese translation resource is missing from the test build."))
        using (var reader = new StreamReader(stream, Encoding.UTF8))
            json = reader.ReadToEnd();
        using var document = JsonDocument.Parse(json);
        Check(document.RootElement.ValueKind == JsonValueKind.Object, "Chinese translation resource is a JSON object");
        var keys = new HashSet<string>(StringComparer.Ordinal);
        var resourceEntries = 0;
        foreach (var entry in document.RootElement.EnumerateObject())
        {
            resourceEntries++;
            Check(keys.Add(entry.Name), "Duplicate Chinese translation key: " + entry.Name);
            Check(!string.IsNullOrWhiteSpace(entry.Name), "Chinese translation key must not be empty");
            Check(entry.Value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(entry.Value.GetString()),
                "Chinese translation must be a non-empty string: " + entry.Name);
        }
        Check(resourceEntries >= 60, $"Chinese translation resource has {resourceEntries} entries; expected at least 60");

        var chinese = Strings.ChineseStrings;
        Check(chinese.Count == resourceEntries, "Loaded Chinese table matches the embedded resource entry count");

        // (b) Formatting placeholders must survive translation: a translated
        // string may not drop, renumber, duplicate or invent {n} placeholders.
        foreach (var entry in chinese)
        {
            var source = Placeholders(entry.Key);
            var translation = Placeholders(entry.Value);
            if (source.Count > 0)
                Check(translation.SetEquals(source), "Chinese translation keeps formatting placeholders: " + entry.Key);
            else
                Check(translation.Count == 0, "Chinese translation adds an unmatched formatting placeholder: " + entry.Key);
        }

        // (c) CurrentUICulture selects the language and unknown keys fall back
        // to the English source text. Always restore the harness culture.
        var originalCulture = CultureInfo.CurrentUICulture;
        try
        {
            CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo("zh-CN");
            Check(Strings.UsesChinese, "zh-CN UI culture selects the Chinese table");
            Check(Strings.Get("Quit") != "Quit" && ContainsChinese(Strings.Get("Quit")), "zh-CN translates Quit");
            Check(ContainsChinese(Strings.Get("Refresh")), "zh-CN returns Chinese text for Refresh");
            CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo("en-US");
            Check(!Strings.UsesChinese, "en-US UI culture selects the English source text");
            Check(Strings.Get("Quit") == "Quit", "en-US returns the English source key for Quit");
            Check(Strings.Get("Refresh") == "Refresh", "en-US returns the English source key for Refresh");
            Check(Strings.Get("A translation key that does not exist") == "A translation key that does not exist",
                "Unknown key falls back to itself");
        }
        finally
        {
            CultureInfo.CurrentUICulture = originalCulture;
        }

        // (d) and (e) need the source tree; a published test binary only runs
        // the embedded table and culture checks above.
        var root = FindRepositoryRoot();
        if (root is null)
        {
            Console.WriteLine("Repository sources are unavailable; skipped translation-key and hard-coded-Chinese source checks.");
            return count;
        }
        var windowsRoot = Path.Combine(root, "desktop", "windows");
        var sources = EnumerateFrontendSources(windowsRoot);
        Check(sources.Count > 0, "Windows frontend C# sources found for the translation coverage scan");
        var lookups = new List<(string File, string Key)>();
        foreach (var source in sources)
        {
            foreach (Match match in LookupPattern.Matches(File.ReadAllText(source)))
                lookups.Add((Path.GetRelativePath(root, source), Unescape(match.Groups[1].Value)));
        }
        Check(lookups.Count >= 30, $"Expected at least 30 Strings.Get/Strings.Format lookups in the Windows sources; found {lookups.Count}");
        foreach (var lookup in lookups)
            Check(chinese.ContainsKey(lookup.Key), $"{lookup.File}: translation key is missing from Strings.zh.json: {lookup.Key}");

        // (e) The user-visible WinForms sources must not keep hard-coded
        // Chinese text; every such string is supposed to come from Strings.
        foreach (var name in FrontendSources)
        {
            var path = Path.Combine(windowsRoot, name);
            if (!File.Exists(path)) throw new Exception("Frontend source file is missing at " + path);
            var offendingLines = FindChineseInLiterals(File.ReadAllText(path));
            Check(offendingLines.Count == 0, name + " contains hard-coded Chinese text in a string literal at line(s) " +
                string.Join(", ", offendingLines.Distinct()));
        }
        return count;
    }

    private static string? FindRepositoryRoot()
    {
        static string? Search(string start)
        {
            try
            {
                for (var directory = new DirectoryInfo(start); directory is not null; directory = directory.Parent)
                    if (File.Exists(Path.Combine(directory.FullName, "desktop", "windows", "SofaTap.Desktop.csproj")))
                        return directory.FullName;
            }
            catch (ArgumentException) { }
            return null;
        }
        return Search(Directory.GetCurrentDirectory()) ?? Search(AppContext.BaseDirectory);
    }

    private static List<string> EnumerateFrontendSources(string windowsRoot)
    {
        var sources = new List<string>();
        foreach (var path in Directory.EnumerateFiles(windowsRoot, "*.cs", SearchOption.AllDirectories))
        {
            var segments = Path.GetRelativePath(windowsRoot, path).Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            if (segments.Any(segment => segment.Equals("tests", StringComparison.OrdinalIgnoreCase)
                || segment.Equals("preview", StringComparison.OrdinalIgnoreCase)
                || segment.Equals("bin", StringComparison.OrdinalIgnoreCase)
                || segment.Equals("obj", StringComparison.OrdinalIgnoreCase))) continue;
            sources.Add(path);
        }
        sources.Sort(StringComparer.Ordinal);
        return sources;
    }

    private static HashSet<int> Placeholders(string text)
    {
        var indexes = new HashSet<int>();
        foreach (Match match in PlaceholderPattern.Matches(text))
            indexes.Add(int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture));
        return indexes;
    }

    private static bool ContainsChinese(string text) => text.Any(IsChinese);

    private static bool IsChinese(char value) =>
        value is >= '\u3000' and <= '\u303f' // CJK punctuation
            or >= '\u3400' and <= '\u4dbf' // CJK ext A
            or >= '\u4e00' and <= '\u9fff' // CJK unified ideographs
            or >= '\uf900' and <= '\ufaff' // CJK compatibility ideographs
            or >= '\uff01' and <= '\uff60'; // full-width forms

    // Decodes a C# escaped literal body so scanned keys match the resource keys.
    private static string Unescape(string text)
    {
        var builder = new StringBuilder(text.Length);
        var index = 0;
        while (index < text.Length)
        {
            if (text[index] == '\\' && TryReadEscape(text, ref index, out var value))
            {
                builder.Append(value);
                continue;
            }
            builder.Append(text[index]);
            index++;
        }
        return builder.ToString();
    }

    private static bool TryReadEscape(string text, ref int index, out char value)
    {
        value = '\0';
        if (index + 1 >= text.Length) return false;
        switch (text[index + 1])
        {
            case '\\': value = '\\'; index += 2; return true;
            case '"': value = '"'; index += 2; return true;
            case '\'': value = '\''; index += 2; return true;
            case '0': value = '\0'; index += 2; return true;
            case 'a': value = '\a'; index += 2; return true;
            case 'b': value = '\b'; index += 2; return true;
            case 'f': value = '\f'; index += 2; return true;
            case 'n': value = '\n'; index += 2; return true;
            case 'r': value = '\r'; index += 2; return true;
            case 't': value = '\t'; index += 2; return true;
            case 'v': value = '\v'; index += 2; return true;
            case 'u': return TryReadHex(text, ref index, 4, 4, out value);
            case 'U': return TryReadHex(text, ref index, 8, 8, out value);
            case 'x': return TryReadHex(text, ref index, 1, 4, out value);
            default: index += 2; return true;
        }
    }

    private static bool TryReadHex(string text, ref int index, int minimum, int maximum, out char value)
    {
        value = '\0';
        var cursor = index + 2;
        var start = cursor;
        var parsed = 0;
        while (cursor < text.Length && cursor - start < maximum && IsHexDigit(text[cursor]))
        {
            parsed = parsed * 16 + HexValue(text[cursor]);
            cursor++;
        }
        if (cursor - start < minimum) return false;
        value = parsed <= char.MaxValue ? (char)parsed : '\0';
        index = cursor;
        return true;
    }

    private static bool IsHexDigit(char value) => value is >= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F';

    private static int HexValue(char value) => value <= '9' ? value - '0' : (value | 0x20) - 'a' + 10;

    // Returns the lines holding a Han/CJK character inside a C# string literal.
    // Comments, identifiers and character literals are ignored; verbatim and
    // regular literals are handled with their real escape rules.
    private static List<int> FindChineseInLiterals(string text)
    {
        var lines = new List<int>();
        var line = 1;
        var index = 0;
        while (index < text.Length)
        {
            var current = text[index];
            if (current == '\n') { line++; index++; continue; }
            if (current == '/' && index + 1 < text.Length && text[index + 1] == '/')
            {
                index += 2;
                while (index < text.Length && text[index] != '\n') index++;
                continue;
            }
            if (current == '/' && index + 1 < text.Length && text[index + 1] == '*')
            {
                index += 2;
                while (index + 1 < text.Length && !(text[index] == '*' && text[index + 1] == '/'))
                {
                    if (text[index] == '\n') line++;
                    index++;
                }
                index = Math.Min(text.Length, index + 2);
                continue;
            }
            if (current == '\'')
            {
                index++;
                while (index < text.Length)
                {
                    if (text[index] == '\\') { index += 2; continue; }
                    if (text[index] == '\'') { index++; break; }
                    if (text[index] == '\n') line++;
                    index++;
                }
                continue;
            }
            if (current == '@' && index + 1 < text.Length && text[index + 1] == '"')
            {
                ScanVerbatimString(text, ref index, ref line, lines);
                continue;
            }
            if (current == '$')
            {
                var verbatim = index + 1 < text.Length && text[index + 1] == '@';
                var quote = index + (verbatim ? 2 : 1);
                if (quote < text.Length && text[quote] == '"')
                {
                    index = verbatim ? quote - 1 : quote;
                    continue;
                }
                index++;
                continue;
            }
            if (current == '"')
            {
                var quoteCount = 0;
                while (index + quoteCount < text.Length && text[index + quoteCount] == '"') quoteCount++;
                if (quoteCount >= 3) { ScanRawString(text, ref index, ref line, lines); continue; }
                ScanRegularString(text, ref index, ref line, lines);
                continue;
            }
            index++;
        }
        return lines;
    }

    private static void ScanRegularString(string text, ref int index, ref int line, List<int> lines)
    {
        index++;
        while (index < text.Length)
        {
            var current = text[index];
            if (current == '\n') { line++; index++; continue; }
            if (current == '\\')
            {
                var escapeIndex = index;
                if (TryReadEscape(text, ref index, out var escaped))
                {
                    if (IsChinese(escaped)) lines.Add(line);
                    continue;
                }
                if (index == escapeIndex) index++;
                continue;
            }
            if (current == '"') { index++; return; }
            if (IsChinese(current)) lines.Add(line);
            index++;
        }
    }

    private static void ScanVerbatimString(string text, ref int index, ref int line, List<int> lines)
    {
        index += 2;
        while (index < text.Length)
        {
            var current = text[index];
            if (current == '"')
            {
                if (index + 1 < text.Length && text[index + 1] == '"') { index += 2; continue; }
                index++;
                return;
            }
            if (current == '\n') line++;
            if (IsChinese(current)) lines.Add(line);
            index++;
        }
    }

    private static void ScanRawString(string text, ref int index, ref int line, List<int> lines)
    {
        var quoteCount = 0;
        while (index + quoteCount < text.Length && text[index + quoteCount] == '"') quoteCount++;
        var contentStart = index + quoteCount;
        var closing = text.IndexOf(new string('"', quoteCount), contentStart, StringComparison.Ordinal);
        var contentEnd = closing < 0 ? text.Length : closing;
        for (var cursor = contentStart; cursor < contentEnd; cursor++)
        {
            if (text[cursor] == '\n') line++;
            if (IsChinese(text[cursor])) lines.Add(line);
        }
        index = closing < 0 ? text.Length : closing + quoteCount;
    }
}
