using System.Globalization;
using System.Text.Json;

namespace SofaTap.Desktop;

// English source text is the key, matching the macOS receiver's NSLocalizedString
// catalog. Chinese translations live in an embedded JSON resource so the
// self-contained single-file build needs no satellite assemblies.
internal static class Strings
{
    internal const string ProductName = "HostTap Remote";
    private const string ResourceName = "Sofatap.Localization.Strings.zh.json";
    private static readonly IReadOnlyDictionary<string, string> Chinese = Load();

    internal static bool UsesChinese => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase);

    internal static string Get(string key) => UsesChinese && Chinese.TryGetValue(key, out var value) ? value : key;

    internal static string Format(string key, params object?[] arguments) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), arguments);

    // Tests inspect the loaded table for coverage and formatting checks.
    internal static IReadOnlyDictionary<string, string> ChineseStrings => Chinese;

    private static IReadOnlyDictionary<string, string> Load()
    {
        using var stream = typeof(Strings).Assembly.GetManifestResourceStream(ResourceName)
            ?? throw new InvalidOperationException("The interface translations are missing from this build.");
        using var document = JsonDocument.Parse(stream);
        if (document.RootElement.ValueKind != JsonValueKind.Object) throw new InvalidDataException("The interface translations are invalid.");
        var table = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var entry in document.RootElement.EnumerateObject())
        {
            if (entry.Value.ValueKind != JsonValueKind.String) throw new InvalidDataException("The interface translations are invalid.");
            var value = entry.Value.GetString();
            if (string.IsNullOrWhiteSpace(value)) throw new InvalidDataException("The interface translations are invalid.");
            if (!table.TryAdd(entry.Name, value)) throw new InvalidDataException("The interface translations contain a duplicate key.");
        }
        return table;
    }
}
