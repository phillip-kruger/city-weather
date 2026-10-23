<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# city-weather

A [House Elves](https://github.com/House-elves) elf for Elf Desk. It shows the weather now and the next three days
(highs, lows, chance of rain) for the cities you pick. Its tab has one card per city, in the order you listed them,
and a line at the top says when it was last updated. If a city can't be found, its card says so and tells you to fix
the name in Settings. The dashboard card gives one sentence, like the first city's temperature now and its rain
chance today. **Refresh** looks everything up again; **Settings** changes the cities or switches to Fahrenheit and mph.

It runs every morning at 06:30, weekends included, once after setup, and whenever you click Refresh. No account or
key is needed.

- **Reads:** Open-Meteo's free geocoding service (geocoding-api.open-meteo.com) to find each city, and its free
  forecast service (api.open-meteo.com) for the weather there. Its own settings on this machine.
- **Writes:** nothing outside this machine. It sends your city names to Open-Meteo to look them up, and saves the
  forecast as a small file in `~/.config/city-weather/forecast/`. Nothing waits for your click, because nothing is
  posted or sent.

## Adding it

Click **+** on Elf Desk and pick **City weather**. In Settings, list your cities separated by semicolons, like
`Paris, France; Tokyo; Austin, Texas` (a country or region after a comma helps when names are shared), then Refresh.

## Running it by hand

    mkdir -p ~/.config/city-weather && echo 'CITIES=Paris, France; Tokyo' > ~/.config/city-weather/config
    jbang CityWeather.java            # prints the weather, saves nothing
    jbang CityWeather.java --save     # saved for its tab on Elf Desk

[AGENTS.md](AGENTS.md) is the guide to writing an elf.
