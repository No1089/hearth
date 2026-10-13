#!/usr/bin/env python3
"""
Home dashboard for the kitchen tablet.

One page (static/index.html) with two modes: an idle slideshow (art gallery +
an immich album) and a touch dashboard (week calendar, shopping list, family
to-dos, office "busy" banner). This server only aggregates; it owns no data:

- lists     → LISTS=todo: the todo app (`shopping` plus its other sections as
              to-dos; it also drives the receipt printer), or LISTS=ha: Home
              Assistant to-do entities (one shopping list + the rest as tabs)
- pocket    → the pocket-money app (balances, goals); chores are kept here, and
              crediting a day needs the parent PIN
- calendar  → Home Assistant calendar entities
- busy      → the Mac busy agent (POST /api/busy) and/or Home Assistant binary
              sensors (any `on` = busy)
- slideshow → frame-art gallery JSON + an immich album (proxied, key stays here)
              + a folder of pictures (IMAGE_DIR)
- weather   → open-meteo
- music     → one Home Assistant media_player (the kitchen Sonos) + its favourites
"""

import hmac, json, os, random, threading, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

from flask import Flask, Response, abort, jsonify, request, send_from_directory

app = Flask(__name__, static_folder=None)
STATIC = Path(__file__).parent / 'static'

def env(name, default=''):
    return os.environ.get(name, default).strip()

# ── add-on runtime (Home Assistant Supervisor) ───────────────────────────────
# As an HA add-on the options live in /data/options.json: option `ha_shopping_list`
# is env HA_SHOPPING_LIST, lists are comma-joined. Environment already set wins, so
# the same image still runs from deploy.env. With a Supervisor token the add-on
# talks to HA through the supervisor proxy and keeps its data in /data.

def _load_addon_options(path):
    try:
        with open(path) as f:
            opts = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return
    for key, val in (opts if isinstance(opts, dict) else {}).items():
        if isinstance(val, list):
            val = ','.join(str(x) for x in val if str(x).strip())
        if val is None or str(val).strip() == '':
            continue
        os.environ.setdefault(key.upper(), str(val))

_load_addon_options(env('OPTIONS_FILE', '/data/options.json'))
if env('SUPERVISOR_TOKEN'):
    os.environ.setdefault('HA_URL', 'http://supervisor/core')
    os.environ.setdefault('HA_TOKEN', env('SUPERVISOR_TOKEN'))
    os.environ.setdefault('HA_WS_URL', 'ws://supervisor/core/websocket')
    if os.path.isdir('/data'):
        os.environ.setdefault('DATA_DIR', '/data')

PORT             = int(env('PORT', '5007'))
# lists: `todo` = the todo app at TODO_URL, `ha` = Home Assistant to-do entities
LISTS            = env('LISTS').lower() or ('todo' if env('TODO_URL') else 'ha')
if LISTS not in ('todo', 'ha'):
    raise SystemExit(f'LISTS must be todo or ha, not {LISTS!r}')
TODO_URL         = env('TODO_URL', 'http://localhost:5002').rstrip('/')
SHOPPING_SECTION = env('SHOPPING_SECTION', 'shopping')
HA_SHOPPING_LIST = env('HA_SHOPPING_LIST', 'todo.shopping_list')
# to-do tabs in HA mode; empty = every other todo.* entity HA has
HA_TODO_LISTS    = [x.strip() for x in env('HA_TODO_LISTS').split(',') if x.strip()]
# to-do view: these sections first, then the rest of the todo app's sections in its order
TODO_FIRST       = [x.strip() for x in env('TODO_FIRST', 'home,kids').split(',') if x.strip()]
TODO_HIDE        = {x.strip() for x in env('TODO_HIDE').split(',') if x.strip()}
POCKET_URL       = env('POCKET_URL').rstrip('/')
POCKET_PIN       = env('POCKET_PIN')
DATA_DIR         = Path(env('DATA_DIR', str(Path(__file__).parent / 'data')))
HA_WAKE_ENTITIES = [e.strip() for e in env('HA_WAKE_ENTITIES').split(',') if e.strip()]
HA_URL           = env('HA_URL').rstrip('/')
HA_TOKEN         = env('HA_TOKEN')
HA_WS_URL        = env('HA_WS_URL')                  # optional; else derived from HA_URL
# calendar.kids|Kids|#e4572e,calendar.family|Family|#3b8ea5
HA_CALENDARS     = [c for c in env('HA_CALENDARS').split(',') if c.strip()]
HA_BUSY_ENTITIES = [e.strip() for e in env('HA_BUSY_ENTITIES').split(',') if e.strip()]
BUSY_LABEL       = env('BUSY_LABEL', 'On a call')
ART_URL          = env('ART_URL').rstrip('/')
IMMICH_URL       = env('IMMICH_URL').rstrip('/')
IMMICH_API_KEY   = env('IMMICH_API_KEY')
IMMICH_ALBUM_IDS = [a.strip() for a in env('IMMICH_ALBUM_IDS').split(',') if a.strip()]
IMAGE_DIR        = env('IMAGE_DIR')                   # a folder of pictures; a subfolder's name is its caption
MUSIC_ENTITY     = env('MUSIC_ENTITY')
MUSIC_MAX_VOLUME = float(env('MUSIC_MAX_VOLUME', '0.6'))
WEATHER_LAT      = env('WEATHER_LAT')
WEATHER_LON      = env('WEATHER_LON')
TIMEZONE         = env('TZ', 'UTC')                   # as an add-on the Supervisor sets TZ to HA's zone

# ── tiny TTL cache ────────────────────────────────────────────────────────────
_cache, _lock = {}, threading.Lock()

def cached(key, ttl, fn, stale_ok=6 * 3600):
    """Return fn() cached for ttl seconds. On failure serve the last good value, but
    only while it is younger than stale_ok: a banner must not stay up because HA went away."""
    now = time.time()
    with _lock:
        hit = _cache.get(key)
    if hit and now - hit[0] < ttl:
        return hit[1]
    try:
        val = fn()
    except Exception as e:
        app.logger.warning('%s: %s', key, e)
        if hit and now - hit[0] < stale_ok:
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

def ha(path, timeout=8):
    return http_json(f'{HA_URL}{path}', headers={'Authorization': f'Bearer {HA_TOKEN}'}, timeout=timeout)

def ha_service(domain, service, data, response=False):
    """Call an HA service; response=True asks for its service_response (e.g. todo.get_items)."""
    q = '?return_response' if response else ''
    return http_json(f'{HA_URL}/api/services/{domain}/{service}{q}', 'POST', data,
                     headers={'Authorization': f'Bearer {HA_TOKEN}'})

def ha_title(ent):
    """`todo.kids_chores` → `Kids Chores`, for entities without a friendly_name."""
    return ent.split('.', 1)[-1].replace('_', ' ').title()

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
        'hasBusy': True,
        'hasWeather': bool(WEATHER_LAT and WEATHER_LON),
        'hasMusic': bool(HA_URL and HA_TOKEN and MUSIC_ENTITY),
        'musicMaxVolume': MUSIC_MAX_VOLUME,
        'hasPocket': bool(POCKET_URL),
        'pocketPinSet': bool(POCKET_PIN),
        'lists': LISTS,
    })

# ── busy + weather (polled every few seconds) ────────────────────────────────

# Pushed by the Mac busy agent (mac/busy-agent): {source: {busy, since, devices, seen}}.
# A report counts only while the agent keeps checking in, so a Mac that sleeps
# or loses the network mid-call can't leave the banner stuck on.
_pushed_busy, _busy_lock, BUSY_STALE_S, MAX_SOURCES = {}, threading.Lock(), 90, 16

@app.route('/api/busy', methods=['POST'])
def api_busy_push():
    b = request.json if isinstance(request.json, dict) else {}
    src = str(b.get('source') or 'mac')[:40]
    busy = bool(b.get('busy'))
    devices = b.get('devices') if isinstance(b.get('devices'), list) else []
    with _busy_lock:
        prev = _pushed_busy.get(src, {})
        _pushed_busy[src] = {'busy': busy, 'devices': [str(d)[:60] for d in devices][:6],
                             'since': prev.get('since') if prev.get('busy') == busy and prev.get('since')
                                      else datetime.now(timezone.utc).isoformat(),
                             'seen': time.time()}
        for old in sorted(_pushed_busy, key=lambda k: _pushed_busy[k]['seen'])[:-MAX_SOURCES]:
            del _pushed_busy[old]                      # anyone on the LAN can post; don't let it grow
    invalidate('busy')
    return jsonify({'ok': True})

def _busy():
    on, since, errors = False, None, 0
    with _busy_lock:
        reports = list(_pushed_busy.values())
    for r in reports:
        if r['busy'] and time.time() - r['seen'] < BUSY_STALE_S:
            on = True
            since = min(since, r['since']) if since else r['since']
    if HA_URL and HA_TOKEN and HA_BUSY_ENTITIES:
        for ent in HA_BUSY_ENTITIES:
            try:
                st = ha(f'/api/states/{ent}', timeout=3)
            except Exception:
                errors += 1                            # HA down must not hide the Mac agent's answer
                continue
            if st and st.get('state') == 'on':
                on = True
                changed = st.get('last_changed')
                since = min(since, changed) if since else changed
    out = {'on': on, 'since': since}
    if errors:
        out['haError'] = errors
    return out

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

def _wake():
    return any((ha(f'/api/states/{e}', timeout=3) or {}).get('state') == 'on' for e in HA_WAKE_ENTITIES)

def _page_version():
    # changes whenever the page or this server is redeployed; the page reloads itself on a change
    return str(int(max((STATIC / 'index.html').stat().st_mtime, Path(__file__).stat().st_mtime)))

@app.route('/api/state')
def api_state():
    out = {'now': datetime.now(timezone.utc).isoformat(), 'version': _page_version()}
    try:
        out['busy'] = cached('busy', 3, _busy)
    except Exception:
        out['busy'] = {'on': False, 'error': True}
    if HA_URL and HA_TOKEN and HA_WAKE_ENTITIES:
        try:
            out['wake'] = cached('wake', 3, _wake, stale_ok=30)   # e.g. kitchen occupancy → page wakes the screen
        except Exception:
            pass
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
        label = parts[1] if len(parts) > 1 and parts[1] else ha_title(ent)
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

# ── lists ─────────────────────────────────────────────────────────────────────
# One shape for the page whichever backend holds the data: a list is
# {items: [{id, text}], recent: [text]} where `recent` = ticked off before and not
# on the list now (tap to re-add). `kind` is `shopping` or a to-do section id.

def _shape(open_items, done_texts):
    open_texts = {i['text'].lower() for i in open_items}
    return {'items': open_items,
            'recent': [t for t in sorted(set(done_texts), key=str.lower) if t.lower() not in open_texts]}

def _pinned_first(ids):
    """TODO_FIRST in that order, then the rest as the backend orders them (stable)."""
    order = {sid: n for n, sid in enumerate(TODO_FIRST)}
    return sorted(ids, key=lambda sid: order.get(sid, len(order))), order

class TodoAppLists:
    """The todo app at TODO_URL: `shopping` is SHOPPING_SECTION, every other section is a tab."""

    def _data(self):
        return http_json(f'{TODO_URL}/api/data')

    def _section(self, kind):
        if kind == 'shopping':
            return SHOPPING_SECTION
        ids = {x['id'] for x in cached('todo-sections', 30, lambda: self._data()['sections'])}
        if kind not in ids or kind in TODO_HIDE or kind == SHOPPING_SECTION:
            abort(404)
        return kind

    @staticmethod
    def _summarise(items, sec):
        mine = [i for i in items if i.get('section_id') == sec]
        return _shape([{'id': i['id'], 'text': i['text']} for i in mine if not i.get('done')],
                      [i['text'] for i in mine if i.get('done')])

    def one(self, kind):
        return self._summarise(self._data()['items'], self._section(kind))

    def todo(self):
        d = self._data()
        secs = {x['id']: x for x in d['sections'] if x['id'] != SHOPPING_SECTION and x['id'] not in TODO_HIDE}
        ids, order = _pinned_first(list(secs))
        return {'sections': [{'id': sid, 'label': secs[sid]['label'], 'pinned': sid in order,
                              **self._summarise(d['items'], sid)} for sid in ids]}

    def add(self, kind, text):
        sec = self._section(kind)
        if not text:
            abort(400)
        same = [i for i in self._data()['items']
                if i.get('section_id') == sec and i['text'].lower() == text.lower()]
        if any(not i.get('done') for i in same):
            return self.one(kind)                         # already on the list
        if same:                                          # bought before: un-tick it
            http_json(f"{TODO_URL}/api/item/{same[0]['id']}/toggle", 'POST', {})
        else:
            http_json(f'{TODO_URL}/api/item', 'POST', {'section_id': sec, 'text': text})
        return self.one(kind)

    def _item(self, kind, item_id):
        sec = self._section(kind)
        item = next((i for i in self._data()['items']
                     if i['id'] == item_id and i.get('section_id') == sec), None)
        if not item:
            abort(404)
        return item

    def done(self, kind, item_id, want):
        if bool(self._item(kind, item_id).get('done')) != want:
            http_json(f'{TODO_URL}/api/item/{item_id}/toggle', 'POST', {})
        return self.one(kind)

    def delete(self, kind, item_id):
        self._item(kind, item_id)
        http_json(f'{TODO_URL}/api/item/{item_id}', 'DELETE')
        return self.one(kind)

    def print(self, kind):
        """The todo app drives the receipt printer."""
        try:
            return http_json(f'{TODO_URL}/api/print-section/{self._section(kind)}', 'POST', {}, timeout=15), 200
        except urllib.error.HTTPError as e:
            return {'ok': False, 'result': f'todo app: HTTP {e.code}'}, 502

class NoEntity(OSError):
    """An option names a to-do entity Home Assistant does not have. An OSError so
    _upstream answers it with the same 502 JSON as any other backend failure."""

class HaLists:
    """Home Assistant to-do entities: HA_SHOPPING_LIST is `shopping`; the tabs are
    HA_TODO_LISTS or, when that is empty, every other todo.* entity. Section ids are
    the entity ids; item ids are HA's item uids."""

    def _shopping(self):
        """HA_SHOPPING_LIST, checked to exist (a minute's cache, like the tabs). Its
        default only exists with the Shopping List integration, and todo.get_items on
        a missing entity is a bare HTTP 500 from HA that names nothing."""
        def fetch():
            try:
                ha(f'/api/states/{HA_SHOPPING_LIST}')
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
                app.logger.warning('HA_SHOPPING_LIST: no entity %s', HA_SHOPPING_LIST)
                return False
            return True
        if not cached('ha-shopping-entity', 60, fetch):
            raise NoEntity(f'HA has no to-do entity {HA_SHOPPING_LIST} (HA_SHOPPING_LIST)')
        return HA_SHOPPING_LIST

    def _labels(self):
        """{entity_id: friendly_name} for the to-do tabs, in tab order; a minute's cache."""
        def fetch():
            if HA_TODO_LISTS:
                states = []
                for ent in HA_TODO_LISTS:
                    try:
                        states.append(ha(f'/api/states/{ent}'))
                    except urllib.error.HTTPError as e:
                        if e.code != 404:
                            raise
                        app.logger.warning('HA_TODO_LISTS: no entity %s', ent)
            else:
                # auto-discover: every todo.* entity except the shopping list; an entity named
                # explicitly in HA_TODO_LISTS is shown even if it is also HA_SHOPPING_LIST
                states = [s for s in ha('/api/states')
                          if s['entity_id'].startswith('todo.') and s['entity_id'] != HA_SHOPPING_LIST]
            return {s['entity_id']: (s.get('attributes') or {}).get('friendly_name') or ha_title(s['entity_id'])
                    for s in states if s['entity_id'] not in TODO_HIDE}
        return cached('ha-todo-lists', 60, fetch)

    def _entity(self, kind):
        if kind == 'shopping':
            return self._shopping()
        if kind not in self._labels():
            abort(404)
        return kind

    def _items(self, ents):
        """{entity_id: [{uid, summary, status}]} in one todo.get_items call."""
        if not ents:
            return {}
        # Ask for both statuses explicitly: the `recent` suggestions and the un-tick in
        # add() need the completed items, and HA documents needs_action as the default.
        r = ha_service('todo', 'get_items', {'entity_id': ents, 'status': ['needs_action', 'completed']},
                       response=True) or {}
        return {ent: (r.get('service_response') or {}).get(ent, {}).get('items') or [] for ent in ents}

    @staticmethod
    def _summarise(items):
        return _shape([{'id': i['uid'], 'text': i['summary']} for i in items if i.get('status') == 'needs_action'],
                      [i['summary'] for i in items if i.get('status') == 'completed'])

    def one(self, kind):
        ent = self._entity(kind)
        return self._summarise(self._items([ent])[ent])

    def todo(self):
        labels = self._labels()
        ids, order = _pinned_first(list(labels))
        items = self._items(ids)
        return {'sections': [{'id': ent, 'label': labels[ent], 'pinned': ent in order,
                              **self._summarise(items[ent])} for ent in ids]}

    def add(self, kind, text):
        ent = self._entity(kind)
        if not text:
            abort(400)
        same = [i for i in self._items([ent])[ent] if (i.get('summary') or '').lower() == text.lower()]
        if any(i.get('status') == 'needs_action' for i in same):
            return self.one(kind)                         # already on the list
        if same:                                          # bought before: un-tick it
            ha_service('todo', 'update_item', {'entity_id': ent, 'item': same[0]['uid'], 'status': 'needs_action'})
        else:
            ha_service('todo', 'add_item', {'entity_id': ent, 'item': text})
        return self.one(kind)

    def _item(self, kind, item_id):
        ent = self._entity(kind)
        item = next((i for i in self._items([ent])[ent] if i.get('uid') == item_id), None)
        if not item:
            abort(404)
        return ent, item

    def done(self, kind, item_id, want):
        ent, item = self._item(kind, item_id)
        status = 'completed' if want else 'needs_action'
        if item.get('status') != status:
            ha_service('todo', 'update_item', {'entity_id': ent, 'item': item_id, 'status': status})
        return self.one(kind)

    def delete(self, kind, item_id):
        ent, _ = self._item(kind, item_id)
        ha_service('todo', 'remove_item', {'entity_id': ent, 'item': [item_id]})
        return self.one(kind)

    def print(self, kind):
        self._entity(kind)
        return {'ok': False, 'result': 'No printer in this mode'}, 200   # the tablet's own printer, if any

lists = HaLists() if LISTS == 'ha' else TodoAppLists()

def _upstream(fn):
    """Backend errors (todo app or HA unreachable, HA refusing a call) → 502 JSON."""
    try:
        return jsonify(fn())
    except OSError as e:                                  # URLError, HTTPError, timeouts
        app.logger.warning('lists (%s): %s', LISTS, e)
        return jsonify({'error': str(e)}), 502

@app.route('/api/todo')
def api_todo():
    """Every to-do section (not shopping) with its open items and done-before suggestions."""
    return _upstream(lists.todo)

@app.route('/api/list/<kind>')
def api_list(kind):
    return _upstream(lambda: lists.one(kind))

@app.route('/api/list/<kind>', methods=['POST'])
def api_list_add(kind):
    text = ' '.join(((request.json or {}).get('text') or '').split())[:120]
    return _upstream(lambda: lists.add(kind, text))       # 404 unknown list, then 400 empty text

@app.route('/api/list/<kind>/<item_id>/done', methods=['POST'])
def api_list_done(kind, item_id):
    want = bool((request.json or {}).get('done', True))
    return _upstream(lambda: lists.done(kind, item_id, want))

@app.route('/api/list/<kind>/<item_id>', methods=['DELETE'])
def api_list_delete(kind, item_id):
    return _upstream(lambda: lists.delete(kind, item_id))

@app.route('/api/list/<kind>/print', methods=['POST'])
def api_list_print(kind):
    try:
        body, status = lists.print(kind)
    except OSError as e:
        return jsonify({'ok': False, 'result': str(e)}), 502
    return jsonify(body), status

# ── pocket money (balances from the pocket app; chores kept here) ───────────

_pin_fails, _pin_lock = {'n': 0, 'until': 0.0}, threading.Lock()
_chores_lock = threading.Lock()

def _check_pin(pin):
    """Parent PIN, with a lockout after 5 wrong tries so it can't be guessed by tapping."""
    if not POCKET_PIN:
        return False
    with _pin_lock:
        now = time.time()
        if now < _pin_fails['until']:
            return False
        if hmac.compare_digest(str(pin or ''), POCKET_PIN):
            _pin_fails['n'] = 0
            return True
        _pin_fails['n'] += 1
        if _pin_fails['n'] >= 5:
            _pin_fails['n'], _pin_fails['until'] = 0, now + 120
        return False

def _chores_file():
    return DATA_DIR / 'chores.json'

def _load_chores():
    try:
        with open(_chores_file()) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {'chores': {}, 'ticks': {}}

def _save_chores(c):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    tmp = _chores_file().with_suffix('.tmp')
    with open(tmp, 'w') as f:
        json.dump(c, f, indent=2)
    os.replace(tmp, _chores_file())                     # atomic: never a half-written file

def _today():
    return datetime.now().date().isoformat()

def _week_start(today):
    t = datetime.fromisoformat(today).date()
    return (t - timedelta(days=t.weekday())).isoformat()

def _pocket_view():
    d = http_json(f'{POCKET_URL}/api/data')
    c = _load_chores()
    today = d.get('today') or _today()
    ticks = c.get('ticks', {}).get(today, {})
    kids = []
    for k in d['kids']:
        day = d.get('days', {}).get(k['id'], {}).get(today, {})
        goals = [{'name': g['name'], 'amount': g.get('amount'), 'balance': g.get('balance'),
                  'pct': g.get('goal_pct'), 'eta': g.get('eta')}
                 for g in k.get('goals', []) if not g.get('completed') and g.get('pct')]
        moves = sorted([{'name': x['name'], 'amount': x['amount'], 'date': x['date']} for x in k.get('additions', [])] +
                       [{'name': x['name'], 'amount': -x['amount'], 'date': x['date']} for x in k.get('deductions', [])],
                       key=lambda x: x['date'], reverse=True)[:5]
        kids.append({'id': k['id'], 'name': k['name'], 'balance': k.get('balance'), 'recent': moves,
                     'daysThisWeek': sum(1 for ds, v in d.get('days', {}).get(k['id'], {}).items()
                                         if v.get('done') and ds >= _week_start(today)),
                     'rate': k.get('default_rate'), 'goals': goals,
                     'workDay': datetime.fromisoformat(today).weekday() in k.get('work_days', [0, 1, 2, 3, 4]),
                     'credited': bool(day.get('done')),
                     'chores': c.get('chores', {}).get(k['id'], []),
                     'ticked': ticks.get(k['id'], [])})
    return {'today': today, 'kids': kids}

def _pocket_required():
    if not POCKET_URL:
        abort(404)

@app.route('/api/pocket')
def api_pocket():
    _pocket_required()
    try:
        return jsonify(_pocket_view())
    except urllib.error.URLError as e:
        return jsonify({'error': str(e)}), 502

@app.route('/api/pocket/<kid_id>/chore/<int:idx>', methods=['POST'])
def api_pocket_chore(kid_id, idx):
    """Kids tick their own chores; no PIN."""
    _pocket_required()
    with _chores_lock:
        c = _load_chores()
        if idx >= len(c.get('chores', {}).get(kid_id, [])):
            abort(404)
        today = _today()
        ticks = c.setdefault('ticks', {})
        for old in [d for d in ticks if d < today]:        # only today matters
            del ticks[old]
        mine = set(ticks.setdefault(today, {}).get(kid_id, []))
        mine ^= {idx}
        ticks[today][kid_id] = sorted(mine)
        _save_chores(c)
    return jsonify(_pocket_view())

@app.route('/api/pocket/<kid_id>/credit', methods=['POST'])
def api_pocket_credit(kid_id):
    """Parent confirms the day: sets today as done in the pocket app (idempotent, not a toggle)."""
    _pocket_required()
    if not _check_pin((request.json or {}).get('pin')):
        return jsonify({'error': 'Wrong PIN' if POCKET_PIN else 'No parent PIN set yet'}), 403
    view = _pocket_view()
    today = view['today']                               # the pocket app's "today", same as `credited` uses
    kid = next((k for k in view['kids'] if k['id'] == kid_id), None)
    if not kid:
        abort(404)
    if not kid['credited']:
        http_json(f'{POCKET_URL}/api/kid/{kid_id}/day/{today}/edit', 'POST', {'rate': kid['rate'], 'doubled': False})
    return jsonify(_pocket_view())

@app.route('/api/pocket/<kid_id>/chores', methods=['POST'])
def api_pocket_set_chores(kid_id):
    """Parent edits a kid's chore list."""
    _pocket_required()
    body = request.json or {}
    if not _check_pin(body.get('pin')):
        return jsonify({'error': 'Wrong PIN' if POCKET_PIN else 'No parent PIN set yet'}), 403
    chores = [' '.join(str(x).split())[:60] for x in body.get('chores', []) if str(x).strip()][:12]
    with _chores_lock:
        c = _load_chores()
        c.setdefault('chores', {})[kid_id] = chores
        c.get('ticks', {}).get(_today(), {}).pop(kid_id, None)   # list changed: start today's ticks over
        _save_chores(c)
    return jsonify(_pocket_view())

@app.route('/api/pocket/<kid_id>/print', methods=['POST'])
def api_pocket_print(kid_id):
    """Kid's receipt (balance, goals, recent spending) on the kitchen printer, via the pocket app."""
    _pocket_required()
    if not any(k['id'] == kid_id for k in http_json(f'{POCKET_URL}/api/data')['kids']):
        abort(404)
    try:
        r = http_json(f'{POCKET_URL}/api/kid/{kid_id}/print', 'POST', {}, timeout=20)
    except urllib.error.HTTPError as e:
        return jsonify({'ok': False, 'error': f'pocket app: HTTP {e.code}'}), 502
    return jsonify({'ok': bool(r.get('ok')), 'error': r.get('error')})

@app.route('/api/pocket/pin', methods=['POST'])
def api_pocket_pin():
    """Lets the page check a PIN before opening the chore editor."""
    _pocket_required()
    ok = _check_pin((request.json or {}).get('pin'))
    return jsonify({'ok': ok}), (200 if ok else 403)

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

IMAGE_EXTS = {'.jpg', '.jpeg', '.png', '.webp', '.gif'}

def _image_dir():
    """Every picture under IMAGE_DIR, subfolders included. Shown like photos (landscapes fill
    the screen), captioned with the subfolder's name. Hidden entries and a NAS's @eaDir skipped."""
    root = Path(IMAGE_DIR)
    out = []
    for p in sorted(root.rglob('*')):
        rel = p.relative_to(root)
        if p.suffix.lower() not in IMAGE_EXTS or not p.is_file() \
                or any(part[0] in '.@' for part in rel.parts):
            continue
        out.append({'kind': 'photo', 'src': '/api/image/' + urllib.parse.quote(rel.as_posix()),
                    'taken': '', 'place': rel.parent.name})
    if not out:
        app.logger.warning('IMAGE_DIR %s: no pictures', IMAGE_DIR)
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
    if IMAGE_DIR:
        try:
            slides += cached('images', 600, _image_dir)
        except Exception:
            pass
    random.shuffle(slides)
    return jsonify(slides)

@app.route('/api/image/<path:name>')
def api_image(name):
    if not IMAGE_DIR:
        abort(404)
    return send_from_directory(IMAGE_DIR, name, max_age=86400)   # refuses anything outside IMAGE_DIR

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
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body, ctype = r.read(), r.headers.get('Content-Type', 'image/jpeg')
    except urllib.error.URLError:
        abort(502)
    return Response(body, content_type=ctype, headers={'Cache-Control': 'public, max-age=86400'})

# ── music (kitchen speaker via Home Assistant) ───────────────────────────────

def ha_ws(messages):
    """Run a few commands over HA's websocket API (media browsing has no REST endpoint)."""
    from websockets.sync.client import connect
    ws_url = HA_WS_URL or HA_URL.replace('http', 'ws', 1) + '/api/websocket'
    out = []
    with connect(ws_url, open_timeout=8, close_timeout=2) as ws:
        ws.recv()
        ws.send(json.dumps({'type': 'auth', 'access_token': HA_TOKEN}))
        if json.loads(ws.recv()).get('type') != 'auth_ok':
            raise RuntimeError('HA websocket auth failed')
        for n, msg in enumerate(messages, 1):
            ws.send(json.dumps({**msg, 'id': n}))
            r = json.loads(ws.recv(timeout=10))
            if not r.get('success'):
                raise RuntimeError(r.get('error'))
            out.append(r['result'])
    return out

def _favourites():
    """Sonos favourites, flattened across the Albums / Playlists / Radio… folders."""
    def browse(t, i):
        return ha_ws([{'type': 'media_player/browse_media', 'entity_id': MUSIC_ENTITY,
                       'media_content_type': t, 'media_content_id': i}])[0]
    favs = []
    for folder in browse('favorites', '').get('children') or []:
        kids = [folder] if folder.get('can_play') else (browse(folder['media_content_type'], folder['media_content_id']).get('children') or [])
        for k in kids:
            if k.get('can_play'):
                favs.append({'id': k['media_content_id'], 'type': k['media_content_type'],
                             'title': k['title'], 'kind': folder['title'],
                             'thumb': k.get('thumbnail') if (k.get('thumbnail') or '').startswith('https://') else None})
    return favs

def _music():
    st = ha(f'/api/states/{MUSIC_ENTITY}')
    a = st.get('attributes', {})
    return {'state': st.get('state'), 'name': a.get('friendly_name'),
            'title': a.get('media_title'), 'artist': a.get('media_artist'),
            'album': a.get('media_album_name'), 'volume': a.get('volume_level'),
            'muted': a.get('is_volume_muted'), 'source': a.get('source'),
            'art': f"/api/music/art?v={urllib.parse.quote(a['entity_picture'])}" if a.get('entity_picture') else None}

def _music_required():
    if not (HA_URL and HA_TOKEN and MUSIC_ENTITY):
        abort(404)

@app.route('/api/music')
def api_music():
    _music_required()
    try:
        return jsonify(cached('music', 1.5, _music))
    except Exception as e:
        return jsonify({'state': 'unavailable', 'error': str(e)}), 502

@app.route('/api/music/favourites')
def api_music_favourites():
    _music_required()
    if request.args.get('refresh'):
        invalidate('favourites')
    try:
        return jsonify(cached('favourites', 600, _favourites))
    except Exception as e:
        return jsonify({'error': str(e)}), 502

@app.route('/api/music/art')
def api_music_art():
    # only the speaker's own artwork proxy, which needs the HA token
    _music_required()
    path = request.args.get('v', '')
    if not path.startswith(f'/api/media_player_proxy/{MUSIC_ENTITY}'):
        abort(404)
    req = urllib.request.Request(f'{HA_URL}{path}', headers={'Authorization': f'Bearer {HA_TOKEN}'})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body, ctype = r.read(), r.headers.get('Content-Type', 'image/jpeg')
    except urllib.error.URLError:
        abort(404)
    return Response(body, content_type=ctype, headers={'Cache-Control': 'public, max-age=3600'})

_thumbs, _thumbs_lock = {}, threading.Lock()

@app.route('/api/music/thumb')
def api_music_thumb():
    """Favourite artwork, fetched here so the tablet needs no internet access of its own.
    Only URLs that appear in the favourites list are fetched: this is not an open proxy."""
    _music_required()
    u = request.args.get('u', '')
    try:
        allowed = {f['thumb'] for f in cached('favourites', 600, _favourites) if f.get('thumb')}
    except Exception:
        abort(502)
    if u not in allowed:
        abort(404)
    with _thumbs_lock:
        hit = _thumbs.get(u)
    if not hit or time.time() - hit[0] > 86400:
        try:
            req = urllib.request.Request(u, headers={'User-Agent': 'hearth/1.0'})
            with urllib.request.urlopen(req, timeout=10) as r:
                hit = (time.time(), r.read(2_000_000), r.headers.get('Content-Type', 'image/jpeg'))
        except urllib.error.URLError:
            abort(502)
        with _thumbs_lock:
            _thumbs[u] = hit
            for k in list(_thumbs)[:-64]:               # a few dozen covers is plenty
                del _thumbs[k]
    return Response(hit[1], content_type=hit[2], headers={'Cache-Control': 'public, max-age=86400'})

@app.route('/api/music/<action>', methods=['POST'])
def api_music_action(action):
    _music_required()
    body = request.json or {}
    ent = {'entity_id': MUSIC_ENTITY}
    if action in ('play', 'pause', 'play_pause', 'next', 'previous'):
        svc = {'play': 'media_play', 'pause': 'media_pause', 'play_pause': 'media_play_pause',
               'next': 'media_next_track', 'previous': 'media_previous_track'}[action]
        ha_service('media_player', svc, ent)
    elif action == 'volume':
        level = max(0.0, min(MUSIC_MAX_VOLUME, float(body.get('level', 0))))
        ha_service('media_player', 'volume_set', {**ent, 'volume_level': round(level, 2)})
    elif action == 'favourite':
        fav = next((f for f in cached('favourites', 600, _favourites) if f['id'] == body.get('id')), None)
        if not fav:
            abort(404)
        ha_service('media_player', 'play_media', {**ent, 'media_content_type': fav['type'],
                                                  'media_content_id': fav['id']})
    else:
        abort(404)
    invalidate('music')
    return jsonify({'ok': True})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=PORT, debug=False, threaded=True)
