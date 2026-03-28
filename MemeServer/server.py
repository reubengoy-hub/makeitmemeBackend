from flask import Flask, request, render_template, jsonify
from flask_socketio import SocketIO, emit, join_room, leave_room
import uuid
import eventlet
import os

eventlet.monkey_patch()

app = Flask(__name__)
app.config['SECRET_KEY'] = os.environ.get('SECRET_KEY', 'secret_memo_key_123')
socketio = SocketIO(app, cors_allowed_origins="*")

rooms = {}

RECONNECT_TIMEOUT  = 30   # segundos para reconectarse tras desconexión
CREATION_TIMEOUT   = 47   # segundos para crear el meme (cliente tiene 45s)
VOTING_SECS_PER_MEME = 10 # segundos por meme a votar (cliente tiene 10s)
RESULTS_TIMEOUT    = 32   # segundos en pantalla de resultados (cliente tiene 30s)

class Room:
    def __init__(self, room_id, host_sid, num_rounds):
        self.room_id   = room_id
        self.host_sid  = host_sid
        self.num_rounds = num_rounds
        self.current_round = 0
        self.state = "LOBBY"
        self.players = {}
        self.memes_this_round  = []
        self.votes_this_round  = {}
        self.phase_id    = 0   # se incrementa con cada nueva fase
        self.phase_timer = None

class Player:
    def __init__(self, sid, name):
        self.sid  = sid
        self.name = name
        self.score = 0
        self.ready_next_round = False
        self.joined_late  = False
        self.disconnected = False
        self.disconnect_timer = None

# ─── Helpers ────────────────────────────────────────────────────────────────

def get_active_players(room):
    return [p for p in room.players.values() if not p.joined_late and not p.disconnected]

def get_player_list(room):
    return [{'sid': p.sid, 'name': p.name, 'score': p.score,
             'is_host': p.sid == room.host_sid, 'disconnected': p.disconnected}
            for p in room.players.values()]

def get_memes_for_voting(room):
    return [{'sid': m['sid'], 'image': m['image']} for m in room.memes_this_round]

def cancel_phase_timer(room):
    if room.phase_timer:
        room.phase_timer.cancel()
        room.phase_timer = None

# ─── Fases del juego ────────────────────────────────────────────────────────

def start_round(room):
    cancel_phase_timer(room)
    room.state = "CREATING"
    room.memes_this_round = []
    room.votes_this_round = {}
    room.phase_id += 1
    for p in room.players.values():
        p.ready_next_round = False
        p.joined_late = False

    socketio.emit('round_started',
                  {'round': room.current_round, 'total_rounds': room.num_rounds},
                  to=room.room_id)
    print(f"Room {room.room_id}: started round {room.current_round}")

    # Timer servidor: avanzar a votación si algún jugador no envió su meme
    pid = room.phase_id
    def creation_timeout():
        r = rooms.get(room.room_id)
        if not r or r.phase_id != pid or r.state != "CREATING":
            return
        print(f"Room {room.room_id}: creation timeout — forcing voting")
        start_voting(r)
    room.phase_timer = eventlet.spawn_after(CREATION_TIMEOUT, creation_timeout)

def start_voting(room):
    cancel_phase_timer(room)
    room.state = "VOTING"
    room.phase_id += 1
    socketio.emit('start_voting', get_memes_for_voting(room), to=room.room_id)
    print(f"Room {room.room_id}: started voting")

    # Timeout dinámico: 10s por meme + 2s de margen
    num_memes = len(room.memes_this_round)
    voting_timeout_secs = VOTING_SECS_PER_MEME * num_memes + 2
    pid = room.phase_id
    def voting_timeout():
        r = rooms.get(room.room_id)
        if not r or r.phase_id != pid or r.state != "VOTING":
            return
        print(f"Room {room.room_id}: voting timeout — auto-voting for idle players")
        active = get_active_players(r)
        meme_sids = [m['sid'] for m in r.memes_this_round]
        for p in active:
            if p.sid not in r.votes_this_round:
                auto_votes = {sid: 9 for sid in meme_sids if sid != p.sid}
                r.votes_this_round[p.sid] = auto_votes
        show_partial_results(r)
    room.phase_timer = eventlet.spawn_after(voting_timeout_secs, voting_timeout)

def show_partial_results(room):
    cancel_phase_timer(room)
    room.state = "RESULTS"
    room.phase_id += 1

    round_scores = {p_sid: 0 for p_sid in room.players}
    for voter_sid, votes in room.votes_this_round.items():
        if isinstance(votes, dict):
            for owner_sid, score in votes.items():
                if owner_sid in round_scores and owner_sid != voter_sid:
                    try:
                        round_scores[owner_sid] += int(score)
                    except (ValueError, TypeError):
                        pass

    for sid, score in round_scores.items():
        if sid in room.players:
            room.players[sid].score += score

    results = [{'sid': p.sid, 'name': p.name,
                'round_score': round_scores.get(p.sid, 0), 'total_score': p.score}
               for p in room.players.values()]
    results.sort(key=lambda x: x['total_score'], reverse=True)

    socketio.emit('partial_results',
                  {'results': results, 'round': room.current_round, 'total_rounds': room.num_rounds},
                  to=room.room_id)
    print(f"Room {room.room_id}: results round {room.current_round}")

    pid = room.phase_id
    def results_timeout():
        r = rooms.get(room.room_id)
        if not r or r.phase_id != pid or r.state != "RESULTS":
            return
        print(f"Room {room.room_id}: results timeout — advancing")
        for p in get_active_players(r):
            p.ready_next_round = True
        _advance_from_results(r)
    room.phase_timer = eventlet.spawn_after(RESULTS_TIMEOUT, results_timeout)

def _advance_from_results(room):
    if room.current_round < room.num_rounds:
        room.current_round += 1
        start_round(room)
    else:
        end_game(room)

def end_game(room):
    cancel_phase_timer(room)
    room.state = "FINISHED"
    results = [{'sid': p.sid, 'name': p.name, 'total_score': p.score}
               for p in room.players.values()]
    results.sort(key=lambda x: x['total_score'], reverse=True)
    socketio.emit('game_over', {'results': results}, to=room.room_id)
    print(f"Room {room.room_id}: Game Over. Winner: {results[0]['name'] if results else 'None'}")
    rooms.pop(room.room_id, None)

def _check_advance(room):
    active = get_active_players(room)
    if not active:
        return
    if room.state == "CREATING":
        if len(room.memes_this_round) >= len(active):
            start_voting(room)
    elif room.state == "VOTING":
        if len(room.votes_this_round) >= len(active):
            show_partial_results(room)
    elif room.state == "RESULTS":
        if sum(1 for p in active if p.ready_next_round) >= len(active):
            _advance_from_results(room)

# ─── Eventos Socket.IO ───────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/memes')
def list_memes():
    memes_dir = os.path.join(app.static_folder, 'memes')
    if not os.path.exists(memes_dir):
        return jsonify([])
    files = [f for f in os.listdir(memes_dir)
             if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
    return jsonify(files)

@socketio.on('create_room')
def on_create_room(data):
    player_name = data.get('name', 'Host')
    num_rounds  = max(1, min(10, int(data.get('num_rounds', 3))))

    room_id = str(uuid.uuid4())[:3].upper()
    while room_id in rooms:
        room_id = str(uuid.uuid4())[:3].upper()

    join_room(room_id)
    rooms[room_id] = Room(room_id, request.sid, num_rounds)
    rooms[room_id].players[request.sid] = Player(request.sid, player_name)

    emit('room_created', {'room_id': room_id, 'player_id': request.sid,
                          'players': get_player_list(rooms[room_id])})
    socketio.emit('player_list_update', get_player_list(rooms[room_id]), to=room_id)
    print(f"Room {room_id} created by {player_name}")

@socketio.on('join_room')
def on_join_room(data):
    room_id     = data.get('room_id', '').upper()
    player_name = data.get('name', 'Player')

    if room_id not in rooms:
        emit('error', {'message': 'Room not found'})
        return

    room = rooms[room_id]

    # Reconexión
    reconnecting = next((p for p in room.players.values()
                         if p.name == player_name and p.disconnected), None)
    if reconnecting:
        if reconnecting.disconnect_timer:
            reconnecting.disconnect_timer.cancel()
            reconnecting.disconnect_timer = None
        old_sid = reconnecting.sid
        del room.players[old_sid]
        reconnecting.sid = request.sid
        reconnecting.disconnected = False
        room.players[request.sid] = reconnecting
        if room.host_sid == old_sid:
            room.host_sid = request.sid
        join_room(room_id)
        emit('joined_room', {'room_id': room_id, 'player_id': request.sid,
                             'state': room.state, 'players': get_player_list(room)})
        socketio.emit('player_list_update', get_player_list(room), to=room_id)
        print(f"Player {player_name} reconnected to {room_id}")
        return

    # Unión normal
    join_room(room_id)
    player = Player(request.sid, player_name)
    if room.state != "LOBBY":
        player.joined_late = True
    room.players[request.sid] = player

    emit('joined_room', {'room_id': room_id, 'player_id': request.sid,
                         'state': room.state, 'players': get_player_list(room)})
    socketio.emit('player_list_update', get_player_list(room), to=room_id)
    print(f"Player {player_name} joined {room_id}")

@socketio.on('start_game')
def on_start_game(data):
    room = rooms.get(data.get('room_id'))
    if room and room.host_sid == request.sid:
        room.current_round = 1
        start_round(room)

@socketio.on('submit_meme')
def on_submit_meme(data):
    room = rooms.get(data.get('room_id'))
    if not room or room.state != "CREATING":
        return
    room.memes_this_round.append({'sid': request.sid, 'image': data.get('image')})
    active = get_active_players(room)
    print(f"Room {room.room_id}: meme {len(room.memes_this_round)}/{len(active)}")
    if len(room.memes_this_round) >= len(active):
        start_voting(room)

@socketio.on('submit_vote')
def on_submit_vote(data):
    room  = rooms.get(data.get('room_id'))
    votes = data.get('votes')
    if not room or room.state != "VOTING":
        return
    room.votes_this_round[request.sid] = votes
    print(f"Room {room.room_id}: vote from {request.sid}")
    if len(room.votes_this_round) >= len(get_active_players(room)):
        show_partial_results(room)

@socketio.on('ready_next_round')
def on_ready_next_round(data):
    room = rooms.get(data.get('room_id'))
    if not room or room.state != "RESULTS":
        return
    if request.sid in room.players:
        room.players[request.sid].ready_next_round = True
    active = get_active_players(room)
    if sum(1 for p in active if p.ready_next_round) >= len(active):
        _advance_from_results(room)

@socketio.on('disconnect')
def on_disconnect():
    for room_id, room in list(rooms.items()):
        if request.sid in room.players:
            p = room.players[request.sid]
            print(f"Player {p.name} disconnected from {room_id}, grace {RECONNECT_TIMEOUT}s")
            p.disconnected = True
            socketio.emit('player_list_update', get_player_list(room), to=room_id)
            _check_advance(room)

            def expel(r_id, p_sid):
                r = rooms.get(r_id)
                if not r:
                    return
                pl = r.players.get(p_sid)
                if not pl or not pl.disconnected:
                    return
                del r.players[p_sid]
                print(f"Player {pl.name} expelled from {r_id}")
                if not r.players:
                    rooms.pop(r_id, None)
                    return
                if r.host_sid == p_sid:
                    new_host = next((s for s, x in r.players.items() if not x.disconnected), None)
                    if new_host:
                        r.host_sid = new_host
                        socketio.emit('host_changed', {'host_sid': new_host}, to=r_id)
                socketio.emit('player_list_update', get_player_list(r), to=r_id)
                _check_advance(r)

            p.disconnect_timer = eventlet.spawn_after(RECONNECT_TIMEOUT, expel, room_id, request.sid)
            break

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    print(f"Starting MakeItMeme Backend on port {port}...")
    socketio.run(app, host='0.0.0.0', port=port)
