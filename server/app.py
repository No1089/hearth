#!/usr/bin/env python3
"""
Home dashboard for the kitchen tablet.

One page (static/index.html) with two modes: an idle slideshow (art gallery +
an immich album) and a touch dashboard (week calendar, shopping list, family
to-dos, office "busy" banner). This server only aggregates; it owns no data:

- lists     → the todo app (sections `shopping` / `family`), which also drives
              the receipt printer
- calendar  → Home Assistant calendar entities
- busy      → Home Assistant binary sensors (any `on` = busy)
- slideshow → frame-art gallery JSON + an immich album (proxied, key stays here)
- weather   → open-meteo
"""

import json, os, random, threading, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, Response, abort, jsonify, request, send_from_directory

app = Flask(__name__, static_folder=None)
STATIC = Path(__file__).parent / 'static'

def env(name, default=''):
    return os.environ.get(name, default).strip()

PORT             = int(env('PORT', '5007'))
TODO_URL         = env('TODO_URL', 'http://localhost:5002').rstrip('/')
LIST_SECTIONS    = {'shopping': env('SHOPPING_SECTION', 'shopping'),
                    'family':   env('FAMILY_SECTION', 'family')}
HA_URL           = env('HA_URL').rstrip('/')
HA_TOKEN         = env('HA_TOKEN')
# calendar.kids|Kids|#e4572e,calendar.family|Family|#3b8ea5
HA_CALENDARS     = [c for c in env('HA_CALENDARS').split(',') if c.strip()]
HA_BUSY_ENTITIES = [e.strip() for e in env('HA_BUSY_ENTITIES').split(',') if e.strip()]
BUSY_LABEL       = env('BUSY_LABEL', 'On a call')
ART_URL          = env('ART_URL').rstrip('/')
IMMICH_URL       = env('IMMICH_URL').rstrip('/')
IMMICH_API_KEY   = env('IMMICH_API_KEY')
IMMICH_ALBUM_IDS = [a.strip() for a in env('IMMICH_ALBUM_IDS').split(',') if a.strip()]
WEATHER_LAT      = env('WEATHER_LAT')
WEATHER_LON      = env('WEATHER_LON')
TIMEZONE         = env('TZ', 'UTC')

# ── tiny TTL cache ────────────────────────────────────────────────────────────
_cache, _lock = {}, threading.Lock()

def cached(key, ttl, fn):
    """Return fn() cached for ttl seconds; on failure serve the last good value."""
    now = time.time()
    with _lock:
        hit = _cache.get(key)
    if hit and now - hit[0] < ttl:
        return hit[1]
    try:
        val = fn()
    except Exception as e:
        app.logger.warning('%s: %s', key, e)
        if hit:
            return hit[1]
        raise
    with _lock:
        _cache[key] = (now, val)
    return val

def invalidate(prefix):
    with _lock:
        for k in [k for k in _cache if k.startswith(prefix)]:
            del _cache[k]

def http_json(url, method='GET', body=None, headers=None, timeout=8):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    return json.loads(raw) if raw else None

def ha(path):
    return http_json(f'{HA_URL}{path}', headers={'Authorization': f'Bearer {HA_TOKEN}'})

# ── pages ─────────────────────────────────────────────────────────────────────

@app.route('/')
def index():
    return send_from_directory(STATIC, 'index.html', max_age=0)

@app.route('/static/<path:name>')
def static_file(name):
    return send_from_directory(STATIC, name, max_age=300)

@app.route('/api/config')
def api_config():
    return jsonify({
        'busyLabel': BUSY_LABEL,
        'hasCalendar': bool(HA_URL and HA_TOKEN and HA_CALENDARS),
        'hasBusy': bool(HA_URL and HA_TOKEN and HA_BUSY_ENTITIES),
        'hasWeather': bool(WEATHER_LAT and WEATHER_LON),
    })

# ── busy + weather (polled every few seconds) ────────────────────────────────

def _busy():
    if not (HA_URL and HA_TOKEN and HA_BUSY_ENTITIES):
        return {'on': False}
    on, since = False, None
    for ent in HA_BUSY_ENTITIES:
        st = ha(f'/api/states/{ent}')
        if st and st.get('state') == 'on':
            on = True
            changed = st.get('last_changed')
            since = min(since, changed) if since else changed
    return {'on': on, 'since': since}

WMO = {0: ('Clear', 'sun'), 1: ('Mostly clear', 'sun'), 2: ('Partly cloudy', 'partly'),
       3: ('Overcast', 'cloud'), 45: ('Fog', 'fog'), 48: ('Fog', 'fog'),
       51: ('Drizzle', 'rain'), 53: ('Drizzle', 'rain'), 55: ('Drizzle', 'rain'),
       61: ('Rain', 'rain'), 63: ('Rain', 'rain'), 65: ('Heavy rain', 'rain'),
       71: ('Snow', 'snow'), 73: ('Snow', 'snow'), 75: ('Snow', 'snow'),
       80: ('Showers', 'rain'), 81: ('Showers', 'rain'), 82: ('Showers', 'storm'),
       95: ('Storm', 'storm'), 96: ('Storm', 'storm'), 99: ('Storm', 'storm')}

def _weather():
    q = urllib.parse.urlencode({
        'latitude': WEATHER_LAT, 'longitude': WEATHER_LON, 'timezone': TIMEZONE,
        'current': 'temperature_2m,weathercode,is_day',
        'daily': 'temperature_2m_max,temperature_2m_min,weathercode,precipitation_probability_max',
        'forecast_days': 7})
    d = http_json(f'https://api.open-meteo.com/v1/forecast?{q}')
    cur, daily = d['current'], d['daily']
    label, icon = WMO.get(cur['weathercode'], ('', 'cloud'))
    days = []
    for i, day in enumerate(daily['time']):
        days.append({'date': day,
                     'max': round(daily['temperature_2m_max'][i]),
                     'min': round(daily['temperature_2m_min'][i]),
                     'rain': daily['precipitation_probability_max'][i],
                     'icon': WMO.get(daily['weathercode'][i], ('', 'cloud'))[1]})
    return {'temp': round(cur['temperature_2m']), 'label': label, 'icon': icon,
            'isDay': bool(cur.get('is_day', 1)), 'days': days}

@app.route('/api/state')
def api_state():
    out = {'now': datetime.now(timezone.utc).isoformat()}
    try:
        out['busy'] = cached('busy', 3, _busy)
    except Exception:
        out['busy'] = {'on': False, 'error': True}
    if WEATHER_LAT and WEATHER_LON:
        try:
            out['weather'] = cached('weather', 600, _weather)
        except Exception:
            out['weather'] = None
    return jsonify(out)

# ── calendar ──────────────────────────────────────────────────────────────────

def _parse_calendars():
    cals = []
    for spec in HA_CALENDARS:
        parts = [p.strip() for p in spec.split('|')]
        ent = parts[0]
        label = parts[1] if len(parts) > 1 and parts[1] else ent.split('.', 1)[-1].replace('_', ' ').title()
        color = parts[2] if len(parts) > 2 and parts[2] else None
        cals.append({'entity': ent, 'label': label, 'color': color})
    return cals

def _calendar(start, end):
    events = []
    for cal in _parse_calendars():
        q = urllib.parse.urlencode({'start': start, 'end': end})
        for ev in ha(f"/api/calendars/{cal['entity']}?{q}") or []:
            s, e = ev.get('start', {}), ev.get('end', {})
            events.append({
                'calendar': cal['label'], 'color': cal['color'],
                'title': ev.get('summary') or '(busy)',
                'location': ev.get('location') or '',
                'allDay': 'date' in s and 'dateTime' not in s,
                'start': s.get('dateTime') or s.get('date'),
                'end': e.get('dateTime') or e.get('date'),
            })
    events.sort(key=lambda x: (x['start'], not x['allDay']))
    return {'calendars': _parse_calendars(), 'events': events}

@app.route('/api/calendar')
def api_calendar():
    if not (HA_URL and HA_TOKEN and HA_CALENDARS):
        return jsonify({'calendars': [], 'events': [], 'unconfigured': True})
    start = request.args.get('start', '')
    end = request.args.get('end', '')
    try:
        datetime.fromisoformat(start); datetime.fromisoformat(end)
    except ValueError:
        abort(400)
    try:
        return jsonify(cached(f'cal:{start}:{end}', 120, lambda: _calendar(start, end)))
    except Exception as e:
        return jsonify({'error': str(e)}), 502

# ── lists (backed by the todo app) ────────────────────────────────────────────

def _todo_data():
    return http_json(f'{TODO_URL}/api/data')

def _section(kind):
    sec = LIST_SECTIONS.get(kind)
    if not sec:
        abort(404)
    return sec

def _list(kind):
    sec = _section(kind)
    items = [i for i in _todo_data()['items'] if i.get('section_id') == sec]
    open_items = [{'id': i['id'], 'text': i['text']} for i in items if not i.get('done')]
    done = sorted({i['text'] for i in items if i.get('done')}, key=str.lower)
    open_texts = {i['text'].lower() for i in open_items}
    return {'items': open_items,
            'recent': [t for t in done if t.lower() not in open_texts]}

@app.route('/api/list/<kind>')
def api_list(kind):
    try:
        return jsonify(_list(kind))
    except urllib.error.URLError as e:
        return jsonify({'error': str(e)}), 502

@app.route('/api/list/<kind>', methods=['POST'])
def api_list_add(kind):
    sec = _section(kind)
    text = ' '.join(((request.json or {}).get('text') or '').split())[:120]
    if not text:
        abort(400)
    items = _todo_data()['items']
    same = [i for i in items if i.get('section_id') == sec and i['text'].lower() == text.lower()]
    if any(not i.get('done') for i in same):
        return jsonify(_list(kind))                       # already on the list
    if same:                                              # bought before: un-tick it
        http_json(f"{TODO_URL}/api/item/{same[0]['id']}/toggle", 'POST', {})
    else:
        http_json(f'{TODO_URL}/api/item', 'POST', {'section_id': sec, 'text': text})
    return jsonify(_list(kind))

def _item_in(kind, item_id):
    sec = _section(kind)
    return next((i for i in _todo_data()['items']
                 if i['id'] == item_id and i.get('section_id') == sec), None)

@app.route('/api/list/<kind>/<item_id>/done', methods=['POST'])
def api_list_done(kind, item_id):
    item = _item_in(kind, item_id)
    if not item:
        abort(404)
    want = bool((request.json or {}).get('done', True))
    if bool(item.get('done')) != want:
        http_json(f'{TODO_URL}/api/item/{item_id}/toggle', 'POST', {})
    return jsonify(_list(kind))

@app.route('/api/list/<kind>/<item_id>', methods=['DELETE'])
def api_list_delete(kind, item_id):
    if not _item_in(kind, item_id):
        abort(404)
    http_json(f'{TODO_URL}/api/item/{item_id}', 'DELETE')
    return jsonify(_list(kind))

@app.route('/api/list/<kind>/print', methods=['POST'])
def api_list_print(kind):
    try:
        res = http_json(f'{TODO_URL}/api/print-section/{_section(kind)}', 'POST', {}, timeout=15)
    except urllib.error.HTTPError as e:
        return jsonify({'ok': False, 'result': f'todo app: HTTP {e.code}'}), 502
    return jsonify(res)

# ── slideshow ─────────────────────────────────────────────────────────────────

def _art():
    works = http_json(f'{ART_URL}/api/works')
    return [{'kind': 'art', 'src': f"{ART_URL}/img/{urllib.parse.quote(w['id'])}",
             'title': w.get('title', ''), 'artist': w.get('artist', ''),
             'date': w.get('date', ''), 'museum': w.get('museum', '')} for w in works]

def _immich_assets():
    hdr = {'x-api-key': IMMICH_API_KEY, 'Accept': 'application/json'}
    out = {}
    for album in IMMICH_ALBUM_IDS:
        a = http_json(f'{IMMICH_URL}/api/albums/{album}', headers=hdr)
        for asset in a.get('assets', []):
            if asset.get('type') != 'IMAGE' or asset.get('isTrashed'):
                continue
            exif = asset.get('exifInfo') or {}
            place = ', '.join(p for p in (exif.get('city'), exif.get('country')) if p)
            out[asset['id']] = {'kind': 'photo', 'src': f"/api/photo/{asset['id']}",
                                'taken': asset.get('localDateTime') or asset.get('fileCreatedAt'),
                                'place': place}
    return out

@app.route('/api/slides')
def api_slides():
    slides = []
    if ART_URL:
        try:
            slides += cached('art', 3600, _art)
        except Exception:
            pass
    if IMMICH_URL and IMMICH_API_KEY and IMMICH_ALBUM_IDS:
        try:
            slides += list(cached('immich', 600, _immich_assets).values())
        except Exception:
            pass
    random.shuffle(slides)
    return jsonify(slides)

@app.route('/api/photo/<asset_id>')
def api_photo(asset_id):
    # only proxy assets that are in the configured album(s)
    try:
        allowed = cached('immich', 600, _immich_assets)
    except Exception:
        abort(502)
    if asset_id not in allowed:
        abort(404)
    req = urllib.request.Request(
        f'{IMMICH_URL}/api/assets/{asset_id}/thumbnail?size=preview',
        headers={'x-api-key': IMMICH_API_KEY})
    with urllib.request.urlopen(req, timeout=20) as r:
        body, ctype = r.read(), r.headers.get('Content-Type', 'image/jpeg')
    return Response(body, content_type=ctype, headers={'Cache-Control': 'public, max-age=86400'})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=PORT, debug=False, threaded=True)
