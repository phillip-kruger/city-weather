import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/city-weather/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "city-weather");
    static final Path FILE = DIR.resolve("config");
    static final int MAX_CITIES = 10;

    List<String> cities = new ArrayList<>();
    boolean fahrenheit;

    static Config load() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        Config c = new Config();
        for (String city : raw.getOrDefault("CITIES", "Melbourne; Brno; Raleigh").split(";")) {
            if (!city.isBlank() && !c.cities.contains(city.strip())) c.cities.add(city.strip());
        }
        c.fahrenheit = Boolean.parseBoolean(raw.getOrDefault("FAHRENHEIT", "false"));
        if (c.cities.isEmpty()) throw new IllegalStateException("Set your cities in city-weather's settings on the desk");
        if (c.cities.size() > MAX_CITIES) throw new IllegalStateException("Pick up to " + MAX_CITIES + " cities in city-weather's settings on the desk");
        return c;
    }
}
