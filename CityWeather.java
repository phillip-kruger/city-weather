///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES Config.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * city-weather: looks up the cities you list on Open-Meteo (free, no key) and saves the weather now and the next
 * three days for its tab on Elf Desk. Without --save it prints what it found (the desk's dry run). It only reads
 * from Open-Meteo, sending the city names and coordinates, and never posts anything.
 */
@Command(name = "city-weather", mixinStandardHelpOptions = true, version = "0.3.0", description = "Shows the weather for your cities on Elf Desk")
public class CityWeather implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path FORECAST = Config.DIR.resolve("forecast").resolve("latest.json");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    static final int MAX_BYTES = 1_000_000;
    /** Tests point these at a stand-in; the elf itself only talks to Open-Meteo. */
    static final String GEO = System.getProperty("city-weather.geo", "https://geocoding-api.open-meteo.com/v1/search");
    static final String FORECAST_API = System.getProperty("city-weather.forecast", "https://api.open-meteo.com/v1/forecast");

    @Option(names = "--save", description = "Save what it found under ~/.config/city-weather/forecast for Elf Desk")
    boolean save;

    @Override
    public Integer call() throws Exception {
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            // The end of a failed run's log is what its tab shows: say what to do, not a stack trace.
            System.err.println(e.getMessage());
            return 1;
        }
        System.out.println("Looking up " + config.cities.size() + " cities...");
        ObjectNode out = JSON.createObjectNode().put("generatedAt", Instant.now().toString())
                .put("tempUnit", config.fahrenheit ? "°F" : "°C").put("windUnit", config.fahrenheit ? "mph" : "km/h");
        ArrayNode cities = out.putArray("cities");
        int ok = 0, failed = 0;
        for (String query : config.cities) {
            ObjectNode city = cities.addObject().put("query", query);
            try {
                if (lookup(query, city, config.fahrenheit)) {
                    ok++;
                    System.out.println(describe(city));
                    if (!city.path("elsewhere").isEmpty()) {
                        System.out.println("  Also in " + String.join(", ", strings(city.path("elsewhere"))) + ": add the country after a comma to pick another.");
                    }
                } else {
                    city.put("error", "notFound");
                    System.out.println("Couldn't find " + query + "." + (city.path("foundIn").isEmpty() ? "" :
                            " It's in " + String.join(", ", strings(city.path("foundIn"))) + ".") + " Fix it in Settings.");
                }
            } catch (Exception e) {
                failed++;
                city.put("error", "unavailable");
                System.err.println("Couldn't get the weather for " + query + ": " + e.getMessage());
            }
        }
        if (save) {
            write(FORECAST, out);
            System.out.println("Saved the weather for " + ok + " of " + config.cities.size() + " cities");
        }
        return ok == 0 && failed > 0 ? 1 : 0;
    }

    /** Finds the city, then fills in its weather. False when Open-Meteo doesn't know the name. */
    static boolean lookup(String query, ObjectNode city, boolean fahrenheit) throws Exception {
        // "Newcastle, UK": the name is searched, the rest says which country or region it's in.
        String[] parts = query.split(",");
        String name = parts[0].strip();
        List<String> hints = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) if (!parts[i].isBlank()) hints.add(parts[i].strip().toLowerCase(Locale.ROOT));
        // A country narrows the search itself, so a small country's city isn't crowded out by bigger ones elsewhere.
        String code = hints.stream().map(CityWeather::countryCode).filter(c -> c != null).findFirst().orElse(null);
        JsonNode found = get(GEO + "?count=" + (hints.isEmpty() ? 10 : 100) + "&language=en&format=json&name=" + enc(name)
                + (code == null ? "" : "&countryCode=" + code)).path("results");
        if (code != null && !found.isArray()) found = get(GEO + "?count=100&language=en&format=json&name=" + enc(name)).path("results");
        JsonNode match = null;
        for (JsonNode r : found) {
            if (!r.path("latitude").isNumber() || !r.path("longitude").isNumber() || !hints.stream().allMatch(h -> in(r, h))) continue;
            if (hints.isEmpty()) {
                match = r;
                break;
            }
            // With a country given, take its biggest place of that name: "Newcastle, UK" is Newcastle upon Tyne.
            if (match == null || named(r, name) && (!named(match, name) || r.path("population").asLong() > match.path("population").asLong())) match = r;
        }
        // The other countries with a place of this name, so the tab can say which to add.
        Set<String> countries = new LinkedHashSet<>();
        for (JsonNode r : found) {
            String country = r.path("country").asText("");
            if (named(r, name) && !country.isEmpty() && (match == null || !country.equals(match.path("country").asText(""))) && countries.size() < 4) {
                countries.add(clip(country, 100));
            }
        }
        ArrayNode elsewhere = city.putArray(match == null ? "foundIn" : "elsewhere");
        if (match == null || hints.isEmpty()) countries.forEach(elsewhere::add);
        if (match == null) return false;
        city.put("name", clip(match.path("name").asText(name), 100));
        String place = String.join(", ", clip(match.path("admin1").asText(""), 100), clip(match.path("country").asText(""), 100));
        city.put("place", place.startsWith(", ") ? place.substring(2) : place.endsWith(", ") ? place.substring(0, place.length() - 2) : place);

        String url = FORECAST_API + "?latitude=" + match.path("latitude").asDouble() + "&longitude=" + match.path("longitude").asDouble()
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&forecast_days=4&timezone=auto"
                + (fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
        JsonNode w = get(url);
        JsonNode cur = w.path("current"), daily = w.path("daily");
        if (!cur.path("temperature_2m").isNumber()) throw new IOException("Open-Meteo sent no weather");
        city.put("temp", Math.round(cur.path("temperature_2m").asDouble()));
        city.put("wind", Math.round(cur.path("wind_speed_10m").asDouble()));
        city.put("description", describe(cur.path("weather_code").asInt(-1)));
        city.put("icon", icon(cur.path("weather_code").asInt(-1)));
        // Day 0 is today (for the card); the next three days are the tab's rows.
        ArrayNode days = city.putArray("days");
        for (int i = 0; i < Math.min(4, daily.path("time").size()); i++) {
            ObjectNode d = days.addObject().put("date", daily.path("time").path(i).asText(""))
                    .put("description", describe(daily.path("weather_code").path(i).asInt(-1)))
                    .put("icon", icon(daily.path("weather_code").path(i).asInt(-1)));
            if (i == 0) {
                d.put("sunrise", clock(daily.path("sunrise").path(0).asText("")));
                d.put("sunset", clock(daily.path("sunset").path(0).asText("")));
            }
            d.put("high", Math.round(daily.path("temperature_2m_max").path(i).asDouble()));
            d.put("low", Math.round(daily.path("temperature_2m_min").path(i).asDouble()));
            if (daily.path("precipitation_probability_max").path(i).isNumber()) d.put("rain", daily.path("precipitation_probability_max").path(i).asInt());
        }
        return true;
    }

    /** True when the place is in the country or region the hint names: by its name, its code, or a name people use for it. */
    static boolean in(JsonNode r, String hint) {
        String code = r.path("country_code").asText("");
        for (String field : new String[] {"country", "country_code", "admin1", "admin2"}) {
            if (hint.equals(r.path(field).asText("").toLowerCase(Locale.ROOT))) return true;
        }
        return !REGIONS.containsKey(hint) && code.equalsIgnoreCase(countryCode(hint));
    }

    /** The place is called the name, or starts with it as a word: Newcastle upon Tyne for Newcastle, not Newcastleton. */
    static boolean named(JsonNode r, String name) {
        String n = r.path("name").asText("").toLowerCase(Locale.ROOT), q = name.toLowerCase(Locale.ROOT);
        return n.equals(q) || n.startsWith(q + " ") || n.startsWith(q + "-");
    }

    /** Parts of a country people write instead of it. They narrow the search to the country, then match the region. */
    static final Map<String, String> REGIONS = Map.of("england", "GB", "scotland", "GB", "wales", "GB", "northern ireland", "GB");
    /** What people write for a country that isn't its English name or its two-letter code. */
    static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("uk", "GB"), Map.entry("britain", "GB"),
            Map.entry("great britain", "GB"), Map.entry("usa", "US"), Map.entry("u.s.", "US"), Map.entry("u.s.a.", "US"),
            Map.entry("america", "US"), Map.entry("united states of america", "US"), Map.entry("uae", "AE"),
            Map.entry("holland", "NL"), Map.entry("czech republic", "CZ"), Map.entry("korea", "KR"), Map.entry("russia", "RU"));

    /** "GB" for "United Kingdom", "uk", "gb" or "England"; null when the hint isn't a country. */
    static String countryCode(String hint) {
        String h = hint.toLowerCase(Locale.ROOT);
        if (REGIONS.containsKey(h)) return REGIONS.get(h);
        if (ALIASES.containsKey(h)) return ALIASES.get(h);
        for (String code : Locale.getISOCountries()) {
            if (h.equals(code.toLowerCase(Locale.ROOT)) || h.equals(Locale.of("", code).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT))) return code;
        }
        return null;
    }

    static String describe(ObjectNode city) {
        JsonNode today = city.path("days").path(0);
        return city.path("name").asText() + (city.path("place").asText("").isEmpty() ? "" : " (" + city.path("place").asText() + ")") + ": " + city.path("temp").asInt() + "°, " + city.path("description").asText().toLowerCase(Locale.ROOT)
                + (today.has("rain") ? ", " + today.path("rain").asInt() + "% chance of rain today" : "");
    }

    /** WMO weather codes, as Open-Meteo sends them, in a few plain words. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "Clear";
            case 1 -> "Mostly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51, 53, 55, 56, 57 -> "Drizzle";
            case 61, 63, 65, 66, 67 -> "Rain";
            case 71, 73, 75, 77, 85, 86 -> "Snow";
            case 80, 81, 82 -> "Showers";
            case 95, 96, 99 -> "Thunderstorm";
            default -> "Unknown";
        };
    }

    /** One emoji per weather code, matching describe(). */
    static String icon(int code) {
        return switch (code) {
            case 0, 1 -> "☀️";
            case 2 -> "⛅";
            case 3 -> "☁️";
            case 45, 48 -> "🌫️";
            case 51, 53, 55, 56, 57 -> "🌦️";
            case 61, 63, 65, 66, 67 -> "🌧️";
            case 71, 73, 75, 77, 85, 86 -> "❄️";
            case 80, 81, 82 -> "🌦️";
            case 95, 96, 99 -> "⛈️";
            default -> "";
        };
    }

    /** "06:12" from Open-Meteo's local "2026-10-23T06:12", or "" if it isn't that shape. */
    static String clock(String t) {
        return t.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}") ? t.substring(11) : "";
    }

    static JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "city-weather (House Elves)").GET().build();
        HttpResponse<java.io.InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (var in = res.body()) {
            if (res.statusCode() != 200) throw new IOException("Open-Meteo answered " + res.statusCode());
            // Read no more than the cap, whatever it says it sends.
            byte[] body = in.readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) throw new IOException("Open-Meteo's answer is too big");
            return JSON.readTree(body);
        }
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Written whole or not at all: the tab may read it at any moment. */
    static void write(Path file, JsonNode n) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new CityWeather()).execute(args));
    }
}
