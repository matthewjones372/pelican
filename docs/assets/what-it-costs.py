#!/usr/bin/env python3
"""
Draws the charts in `docs/what-it-costs.md` from that page's own tables, so a
chart cannot show a number the table beside it does not. Retaking a benchmark
means editing the table and running this again; nothing else holds the data.

Standard library only, and the SVGs are committed because GitHub renders them
from the repository. Each chart is written twice, for the light and dark
themes, and the page picks between them with `<picture>`.

    python3 docs/assets/what-it-costs.py           # rewrite the SVGs
    python3 docs/assets/what-it-costs.py --check   # fail if they are stale

Colours are the first three slots of a palette checked for colour-blind
separation in both themes. The rows are labelled in text, so no chart relies
on colour alone, and the tables stay the accessible view of every number.
"""
import math
import pathlib
import re
import sys
from xml.sax.saxutils import escape

HERE = pathlib.Path(__file__).parent
PAGE = HERE.parent / "what-it-costs.md"
OUT = HERE / "what-it-costs"

THEMES = {
    "light": dict(surface="#ffffff", ink="#0b0b0b", ink2="#52514e", muted="#6b6a65",
                  grid="#e1e0d9", axis="#c3c2b7",
                  series=["#2a78d6", "#eb6834", "#1baf7a"]),
    "dark": dict(surface="#0d1117", ink="#f0efec", ink2="#c3c2b7", muted="#9a9890",
                 grid="#2c2c2a", axis="#383835",
                 series=["#3987e5", "#d95926", "#199e70"]),
}
FONT = 'font-family="system-ui, -apple-system, Segoe UI, sans-serif"'


# ------------------------------------------------------------------ reading the page

def section(md, heading):
    """The text under a heading, up to the next heading of any level."""
    start = md.index(heading + "\n")
    rest = md[start + len(heading) + 1:]
    end = re.search(r"^#{2,3} ", rest, re.M)
    return rest[: end.start()] if end else rest


def tables(text):
    """Every pipe table in `text`, as lists of cell lists, header row dropped."""
    found, rows = [], []
    for line in text.splitlines() + [""]:
        if line.startswith("|"):
            rows.append([c.strip() for c in line.strip("|").split("|")])
        elif rows:
            found.append([r for r in rows[2:]])
            rows = []
    return found


UNITS = {"ns": 1.0, "µs": 1e3, "ms": 1e6}


def measure(cell):
    """`1584 ± 68ns` or `79.8 ± 4.5µs` as (mean, error) in nanoseconds."""
    m = re.fullmatch(r"\**([\d.,]+) ± ([\d.,]+)(ns|µs)\**", cell.strip())
    scale = UNITS[m.group(3)]
    return float(m.group(1).replace(",", "")) * scale, float(m.group(2).replace(",", "")) * scale


def size(cell):
    """`3185 ± 64B` or `14,028 ± 116B` as (mean, error) in bytes."""
    m = re.fullmatch(r"\**([\d,]+) ± ([\d,]+)B\**", cell.strip())
    return float(m.group(1).replace(",", "")), float(m.group(2).replace(",", ""))


def latency(cell):
    """`663µs` or `1.4ms` in milliseconds."""
    m = re.fullmatch(r"([\d.]+)(µs|ms)", cell)
    return float(m.group(1)) * UNITS[m.group(2)] / 1e6


def label(cell):
    return re.sub(r"[*`]", "", cell)


# ------------------------------------------------------------------ drawing

def nice_ticks(top, count=4):
    raw = top / count
    mag = 10 ** math.floor(math.log10(raw))
    step = next(s * mag for s in (1, 2, 2.5, 5, 10) if s * mag >= raw)
    ticks = [0.0]
    while ticks[-1] < top:
        ticks.append(round(ticks[-1] + step, 10))
    return ticks


def fmt(v):
    if v >= 1000 and v % 1000 == 0:
        return f"{int(v) // 1000}k"
    if v >= 1000:
        return f"{v / 1000:g}k"
    return f"{v:g}"


class Svg:
    def __init__(self, w, h, theme, title):
        self.w, self.h, self.t = w, h, THEMES[theme]
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" role="img" aria-label="{escape(title)}" {FONT}>',
            f"<title>{escape(title)}</title>",
            f'<rect width="{w}" height="{h}" fill="{self.t["surface"]}"/>',
        ]

    def text(self, x, y, s, fill=None, size=12, anchor="start", weight=None):
        w = f' font-weight="{weight}"' if weight else ""
        self.parts.append(
            f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" fill="{fill or self.t["ink"]}" '
            f'text-anchor="{anchor}"{w}>{escape(s)}</text>')

    def line(self, x1, y1, x2, y2, stroke, width=1, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{stroke}" stroke-width="{width}"{d}/>')

    def raw(self, s):
        self.parts.append(s)

    def done(self):
        return "\n".join(self.parts + ["</svg>"]) + "\n"


def row_chart(theme, title, groups, panels, label_w=270):
    """Rows of labelled measurements, one panel per measure, each on its own axis.

    `groups` is [(group title or None, [(label, series index, [(mean, err) per panel])])].
    A panel is (heading, unit divisor, draw intervals?). Time panels draw a dot
    with its 99.9% interval, because whether two intervals overlap is the
    finding; allocation is a bar, because its error is a few bytes.
    """
    t = THEMES[theme]
    panel_w, gap, row_h, group_h, top, bottom = 200, 36, 26, 22, 40, 30
    rows = sum(len(r) for _, r in groups) + sum(1 for g, _ in groups if g)
    h = top + rows * row_h + sum(6 for _ in groups) + bottom
    w = label_w + len(panels) * (panel_w + gap)
    svg = Svg(w, h, theme, title)

    tops = []
    for i, (_, div, _) in enumerate(panels):
        hi = max((v[i][0] + v[i][1]) / div for _, rs in groups for _, _, v in rs)
        tops.append(nice_ticks(hi))

    def px(i, v):
        x0 = label_w + i * (panel_w + gap)
        return x0 + v / tops[i][-1] * panel_w

    y = top
    marks = []
    for gtitle, rs in groups:
        if gtitle:
            svg.text(8, y + 16, gtitle, fill=t["ink2"], size=12, weight="600")
            y += row_h
        for name, s, vals in rs:
            cy = y + row_h / 2
            svg.text(label_w - 12, cy + 4, name, fill=t["ink"], size=12, anchor="end")
            marks.append((cy, s, vals))
            y += row_h
        y += 6
    plot_bottom = y - 6

    for i, (heading, div, intervals) in enumerate(panels):
        x0 = label_w + i * (panel_w + gap)
        svg.text(x0, 20, heading, fill=t["ink2"], size=12, weight="600")
        for tick in tops[i]:
            x = px(i, tick)
            svg.line(x, top - 6, x, plot_bottom, t["grid"])
            svg.text(x, plot_bottom + 16, fmt(tick), fill=t["muted"], size=11, anchor="middle")
        svg.line(x0, top - 6, x0, plot_bottom, t["axis"])
        for cy, s, vals in marks:
            mean, err = vals[i][0] / div, vals[i][1] / div
            colour = t["series"][s]
            tip = f"{mean:,.4g} ± {err:,.3g}"
            if intervals:
                svg.line(px(i, mean - err), cy, px(i, mean + err), cy, colour, 2)
                for edge in (mean - err, mean + err):
                    svg.line(px(i, edge), cy - 5, px(i, edge), cy + 5, colour, 2)
                svg.raw(f'<circle cx="{px(i, mean):.1f}" cy="{cy:.1f}" r="5" fill="{colour}" '
                        f'stroke="{t["surface"]}" stroke-width="2"><title>{tip}</title></circle>')
            else:
                x1 = px(i, mean)
                svg.raw(f'<rect x="{x0:.1f}" y="{cy - 7:.1f}" width="{max(x1 - x0, 1):.1f}" height="14" '
                        f'rx="2" fill="{colour}"><title>{tip}</title></rect>')
    return svg.done()


def latency_chart(theme, title, runs):
    """p50 and p99 against offered rate, one line per server, on a log axis.

    A rate the server did not keep up with is a hollow marker joined by a
    dashed line, because its latencies are a queue growing, not a service time.
    """
    t = THEMES[theme]
    rates = sorted({r for rs in runs.values() for r, *_ in rs})
    panel_w, gap, left, top, plot_h = 300, 70, 56, 64, 220
    w, h = left + 2 * panel_w + gap + 20, top + plot_h + 50
    svg = Svg(w, h, theme, title)
    lo, hi = 0.1, 10_000.0
    decades = [10.0 ** e for e in range(-1, 5)]

    def py(ms):
        return top + plot_h - (math.log10(ms) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)) * plot_h

    # Legend first, above both panels.
    lx = left
    for s, server in enumerate(runs):
        c = t["series"][s]
        svg.line(lx, 16, lx + 22, 16, c, 2)
        svg.raw(f'<circle cx="{lx + 11}" cy="16" r="4" fill="{c}"/>')
        svg.text(lx + 30, 20, f"Pelican on {server.capitalize()}", fill=t["ink"], size=12)
        lx += 170
    svg.raw(f'<circle cx="{lx + 11}" cy="16" r="4" fill="{t["surface"]}" stroke="{t["ink2"]}" stroke-width="2"/>')
    svg.text(lx + 22, 20, "did not keep up", fill=t["ink2"], size=12)

    for p, (heading, col) in enumerate((("Median latency, ms (log)", 0), ("p99 latency, ms (log)", 1))):
        x0 = left + p * (panel_w + gap)
        step = panel_w / (len(rates) - 1)

        def px(rate):
            return x0 + rates.index(rate) * step

        svg.text(x0, top - 14, heading, fill=t["ink2"], size=12, weight="600")
        for d in decades:
            svg.line(x0, py(d), x0 + panel_w, py(d), t["grid"])
            svg.text(x0 - 8, py(d) + 4, f"{d:g}", fill=t["muted"], size=11, anchor="end")
        for r in rates:
            svg.text(px(r), top + plot_h + 18, fmt(r), fill=t["muted"], size=11, anchor="middle")
        svg.line(x0, top + plot_h, x0 + panel_w, top + plot_h, t["axis"])
        svg.text(x0 + panel_w / 2, top + plot_h + 38, "requests per second offered",
                 fill=t["muted"], size=11, anchor="middle")
        for s, (server, rs) in enumerate(runs.items()):
            c = t["series"][s]
            pts = [(px(r), py(vals[col]), kept) for r, vals, kept in rs]
            for (x1, y1, _), (x2, y2, kept) in zip(pts, pts[1:]):
                svg.line(x1, y1, x2, y2, c, 2, None if kept else "4 3")
            for (x, y, kept), (r, vals, _) in zip(pts, rs):
                fill = c if kept else t["surface"]
                svg.raw(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="{fill}" stroke="{c}" stroke-width="2">'
                        f'<title>{server}, {r:,} req/s: {vals[col]:g}ms</title></circle>')
    return svg.done()


# ------------------------------------------------------------------ the charts

def charts(md):
    out = {}

    get_rows = tables(section(md, "## Pekko, in memory"))[0]
    post_rows = tables(section(md, "### Reading a body on Pekko"))[0]
    names = {
        "Pelican, handler on the dispatcher": ("Pelican, handler on the dispatcher", 0),
        "Pekko, idiomatic (PathMatchers + parameterOptional)": ("by hand, idiomatic", 1),
        "Pekko, PathMatchers with the query read off the request": ("by hand, PathMatchers only", 1),
        "Pekko, one directive and both read off the request": ("by hand, one directive", 1),
    }
    get = [(names[label(r[0])][0], names[label(r[0])][1], [measure(r[1]), size(r[2])])
           for r in get_rows if label(r[0]) in names]
    out["pekko-get"] = lambda th: row_chart(
        th, "Pekko, in memory, GET: Pelican against three hand-written routes",
        [(None, get)], [("Time per request, ns", 1, True), ("Allocated per request, bytes", 1, False)])

    def post_name(cell):
        c = label(cell)
        if "virtual" in c:
            return "Pelican, handler on a virtual thread", 2
        if c.startswith("Pelican"):
            return "Pelican, handler on the dispatcher", 0
        return "by hand, extractStrictEntity", 1

    post = [(*post_name(r[0]), [measure(r[1]), size(r[2])]) for r in post_rows]
    out["pekko-post"] = lambda th: row_chart(
        th, "Pekko, in memory, JSON POST: Pelican against a hand-written route",
        [(None, post)], [("Time per request, µs", 1e3, True), ("Allocated per request, bytes", 1, False)])

    jetty_rows = tables(section(md, "## Jetty, in memory"))[0]

    def jetty_name(cell):
        c = label(cell)
        if c.startswith("Jetty core"):
            return "Jetty core, by hand", 1
        if "virtual" in c:
            return "Pelican, handler on a virtual thread", 2
        return "Pelican, handler on the Jetty thread", 0

    jetty = [(*jetty_name(r[0]), [measure(r[1]), size(r[2])]) for r in jetty_rows]
    out["jetty"] = lambda th: row_chart(
        th, "Jetty, through a LocalConnector: Pelican against a hand-written handler",
        [("GET /items/{itemId}", jetty[:3]), ("POST /items", jetty[3:])],
        [("Time per request, µs", 1e3, True), ("Allocated per request, bytes", 1, False)])

    wire = {}
    for r in tables(section(md, "## Pekko and Jetty over a socket"))[0]:
        wire.setdefault(r[0], []).append((int(r[1]), (latency(r[2]), latency(r[3])), r[8] == "yes"))
    out["socket"] = lambda th: latency_chart(
        th, "Pelican on Pekko and on Jetty over a loopback socket: latency against offered rate", wire)

    decode = tables(section(md, "## Decoding a body: Jackson and kotlinx"))[0]
    columns = [("Jackson, via String", 0), ("Jackson, via bytes", 0),
               ("kotlinx, via String", 1), ("kotlinx, via bytes", 1)]
    groups = []
    for r in decode:
        rows = []
        for (name, s), cell in zip(columns, r[1:]):
            t, b = cell.split(", ")
            rows.append((name, s, [measure(t), (float(b.rstrip("B").replace(",", "")), 0.0)]))
        groups.append((f"{r[0]} body", rows))
    # The three sizes are two orders of magnitude apart and share no useful
    # scale, so each is drawn on its own axes and the three are stacked.
    def decode_chart(th):
        parts = []
        for g in groups:
            large = g[0].startswith("64")
            parts.append(row_chart(
                th, f"Decoding a {g[0]}", [g],
                [(f"Time per decode, {'µs' if large else 'ns'}", 1e3 if large else 1, True),
                 ("Allocated per decode, bytes", 1, False)], label_w=200))
        return stack(th, "Decoding a body: Jackson and kotlinx, from a String and from bytes", parts)

    out["decode"] = decode_chart
    return out


def stack(theme, title, parts):
    """Several charts one above the other, in one image."""
    sizes = [tuple(int(v) for v in re.search(r'width="(\d+)" height="(\d+)"', p).groups()) for p in parts]
    w, h = max(sw for sw, _ in sizes), sum(sh for _, sh in sizes)
    svg = Svg(w, h, theme, title)
    y = 0
    for part, (_, sh) in zip(parts, sizes):
        svg.raw(part.replace("<svg ", f'<svg y="{y}" ', 1).rstrip("\n"))
        y += sh
    return svg.done()


def main():
    md = PAGE.read_text(encoding="utf-8")
    check = "--check" in sys.argv[1:]
    stale = []
    OUT.mkdir(exist_ok=True)
    for name, draw in charts(md).items():
        for theme in THEMES:
            path = OUT / f"{name}-{theme}.svg"
            svg = draw(theme)
            if check:
                if not path.exists() or path.read_text(encoding="utf-8") != svg:
                    stale.append(path)
            else:
                path.write_text(svg, encoding="utf-8")
    if stale:
        print("Stale charts; run python3 docs/assets/what-it-costs.py:", *stale, sep="\n  ")
        sys.exit(1)


if __name__ == "__main__":
    main()
