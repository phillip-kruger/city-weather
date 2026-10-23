// city-weather's tab on Elf Desk: one card per city, with the weather now and the next three days.
// It runs in the desk's sandboxed frame: everything goes through `elf`, and only what elf.yaml declares.
import {
    LitElement, html, css, shared, cardStyles, spinner, ago, runTime, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

/** What the elf saved last, or null before its first run. */
async function forecast() {
    return (await elf.readJson('forecast', 'latest.json')) ?? null;
}

/** "A, B and C" */
function and(list) {
    return list.length < 2 ? (list[0] ?? '') : `${list.slice(0, -1).join(', ')} and ${list[list.length - 1]}`;
}

/** "Tue" for a date like 2026-10-27. */
function weekday(date) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date ?? '');
    return m ? new Date(+m[1], m[2] - 1, +m[3]).toLocaleDateString(undefined, { weekday: 'long' }) : '';
}

class CityWeatherTab extends LitElement {

    static properties = {
        _data: { state: true },
        _loading: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .cities { display: grid; gap: 14px; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
        .city { padding: 18px 20px; display: grid; gap: 12px; align-content: start; }
        .city h2 { margin: 0; font-size: 20px; font-weight: 600; }
        .place { margin: 2px 0 0; font-size: 13px; color: var(--muted); }
        .now { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
        .temp { font-size: 34px; font-weight: 600; letter-spacing: -0.022em; }
        .icon { font-size: 30px; }
        .sun { margin: 0; color: var(--muted); font-size: 14px; }
        .desc, .wind { color: var(--muted); font-size: 14px; }
        table { border-collapse: collapse; width: 100%; font-size: 14px; }
        th { text-align: left; font-weight: 400; color: var(--muted); font-size: 13px; padding: 0 0 4px; }
        td { padding: 4px 0; }
        th.n, td.n { text-align: right; padding-left: 10px; white-space: nowrap; }
        .missing { margin: 0; color: var(--muted); font-size: 14px; }
        .empty { color: var(--muted); padding: 18px; margin: 0; }
        .skeleton.big { height: 40vh; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._loading = true;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        // A run started or ended (the refresh, the morning schedule): read again.
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        this._error = null;
        try {
            this._data = await forecast();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._loading = false;
        }
    }

    _city(c) {
        const d = this._data;
        const title = c.name || c.query;
        if (c.error === 'notFound') {
            return html`<section class="panel city"><h2>${c.query}</h2>
                <p class="missing">${c.foundIn?.length
                    ? `Couldn't find this city there. There's one in ${and(c.foundIn)}: fix the country in Settings, then Refresh.`
                    : "Couldn't find this city. Fix the name in Settings, then Refresh."}</p></section>`;
        }
        if (c.error) {
            return html`<section class="panel city"><h2>${title}</h2>
                <p class="missing">Couldn't get the weather for this city. Refresh to try again.</p></section>`;
        }
        const sun = c.days?.[0]?.sunrise && c.days[0].sunset ? c.days[0] : null;
        const rows = (c.days ?? []).slice(1, 4);
        return html`<section class="panel city">
            <div><h2>${title}</h2>${c.place ? html`<p class="place">${c.place}</p>` : ''}</div>
            ${c.elsewhere?.length ? html`<p class="missing">Not this one? There's also one in ${and(c.elsewhere)}. In Settings,
                add the country after a comma, like ${c.query}, ${c.elsewhere[0]}.</p>` : ''}
            <div class="now"><span class="icon" aria-hidden="true">${c.icon}</span><span class="temp">${c.temp}${d.tempUnit}</span><span class="desc">${c.description}</span>
                <span class="wind">Wind ${c.wind} ${d.windUnit}</span></div>
            ${sun ? html`<p class="sun">Sunrise ${sun.sunrise} · Sunset ${sun.sunset}</p>` : ''}
            <table>
                <thead><tr><th>Day</th><th>Weather</th><th class="n">High</th><th class="n">Low</th><th class="n">Rain</th></tr></thead>
                <tbody>${rows.map(r => html`<tr><td>${weekday(r.date)}</td><td><span aria-hidden="true">${r.icon}</span> ${r.description}</td>
                    <td class="n">${r.high}${d.tempUnit}</td><td class="n">${r.low}${d.tempUnit}</td>
                    <td class="n">${r.rain == null ? '' : `${r.rain}%`}</td></tr>`)}</tbody>
            </table>
        </section>`;
    }

    render() {
        const d = this._data;
        const at = d?.generatedAt ? new Date(d.generatedAt) : null;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>City weather</h1>
                    <p class="sub">${at ? `Updated ${runTime(at).toLowerCase()}.` : this._loading ? '' : 'Nothing looked up yet.'}</p>
                </div>${settingsButton(null, 'City weather settings')}</div>
                <elf-run-bar command="read" label="Refresh" note="Looks up your cities on Open-Meteo and saves the weather here. Sends nothing else."
                    @run-finished=${() => this._load()}></elf-run-bar>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._loading && !d ? html`<div class="skeleton big"></div>` : ''}
                ${d ? (d.cities?.length ? html`<div class="cities">${d.cities.map(c => this._city(c))}</div>`
                        : html`<p class="panel empty">Set your cities in Settings, then Refresh.</p>`)
                    : !this._loading ? html`<p class="panel empty">Set your cities in Settings, then Refresh.</p>` : ''}
            </div>
        `;
    }
}

/** The dashboard card: the first city's temperature now and its rain chance today. It asks for you if a city needs fixing. */
class CityWeatherCard extends LitElement {

    static properties = { _data: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._data = await forecast();
            this._state = await elf.state();
        } catch (e) {
            this._data = null;
        }
        this._loaded = true;
        elf.card({ attention: !!this._data?.cities?.some(c => c.error === 'notFound') });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const d = this._data;
        const first = d?.cities?.find(c => !c.error);
        const rain = first?.days?.[0]?.rain;
        let say;
        if (!d?.cities?.length) say = html`Nothing looked up yet. <a href="#">Set your cities and Refresh</a>.`;
        else if (!first) say = html`<a href="#">Fix your cities in Settings</a>: none could be found.`;
        else say = html`<a href="#">${first.name}</a> is ${first.temp}${d.tempUnit} now, ${first.description.toLowerCase()}${rain == null ? '' : `, with a ${rain}% chance of rain today`}.`;
        const running = this._state?.running?.read;
        const at = d?.generatedAt ? new Date(d.generatedAt) : null;
        return html`<p class="say">${say}</p>
            <div class="meta">${running ? html`<span class="running" role="status">${spinner}Refreshing, started ${ago(running)}</span>`
                : at ? html`<span>Updated ${runTime(at)}</span>` : ''}</div>`;
    }
}

customElements.define('city-weather-tab', CityWeatherTab);
customElements.define('city-weather-card', CityWeatherCard);
document.body.append(document.createElement(elf.view === 'card' ? 'city-weather-card' : 'city-weather-tab'));
