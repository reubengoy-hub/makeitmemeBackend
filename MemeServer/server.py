from flask import Flask, request
from flask_socketio import SocketIO, emit, join_room, leave_room
import uuid
import eventlet
import os

eventlet.monkey_patch()

app = Flask(__name__)
app.config['SECRET_KEY'] = os.environ.get('SECRET_KEY', 'secret_memo_key_123')
socketio = SocketIO(app, cors_allowed_origins="*")

rooms = {}

RECONNECT_TIMEOUT = 30  # segundos para reconectarse antes de ser expulsado

class Room:
    def __init__(self, room_id, host_sid, num_rounds):
        self.room_id = room_id
        self.host_sid = host_sid
        self.num_rounds = num_rounds
        self.current_round = 0
        self.state = "LOBBY"
        self.players = {}
        self.memes_this_round = []
        self.votes_this_round = {}

class Player:
    def __init__(self, sid, name):
        self.sid = sid
        self.name = name
        self.score = 0
        self.ready_next_round = False
        self.joined_late = False
        self.disconnected = False
        self.disconnect_timer = None

@app.route('/')
def index():
    return "MakeItMeme Backend Running"

@socketio.on('create_room')
def on_create_room(data):
    player_name = data.get('name', 'Host')
    num_rounds = max(1, min(10, int(data.get('num_rounds', 3))))

    room_id = str(uuid.uuid4())[:3].upper()
    while room_id in rooms:
        room_id = str(uuid.uuid4())[:3].upper()

    join_room(room_id)

    rooms[room_id] = Room(room_id, request.sid, num_rounds)
    player = Player(request.sid, player_name)
    rooms[room_id].players[request.sid] = player

    emit('room_created', {'room_id': room_id, 'player_id': request.sid, 'players': get_player_list(rooms[room_id])})
    emit('player_list_update', get_player_list(rooms[room_id]), to=room_id)
    print(f"Room {room_id} created by {player_name}")

@socketio.on('join_room')
def on_join_room(data):
    room_id = data.get('room_id', '').upper()
    player_name = data.get('name', 'Player')

    if room_id not in rooms:
        emit('error', {'message': 'Room not found'})
        return

    room = rooms[room_id]

    # Comprobar si es una reconexión de un jugador desconectado
    reconnecting = next(
        (p for p in room.players.values() if p.name == player_name and p.disconnected),
        None
    )

    if reconnecting:
        # Cancelar el temporizador de expulsión
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
        emit('joined_room', {'room_id': room_id, 'player_id': request.sid, 'state': room.state, 'players': get_player_list(room)})
        socketio.emit('player_list_update', get_player_list(room), to=room_id)
        print(f"Player {player_name} reconnected to {room_id}")
        return

    # Unión normal
    join_room(room_id)
    player = Player(request.sid, player_name)
    if room.state != "LOBBY":
        player.joined_late = True

    room.players[request.sid] = player

    emit('joined_room', {'room_id': room_id, 'player_id': request.sid, 'state': room.state, 'players': get_player_list(room)})
    socketio.emit('player_list_update', get_player_list(room), to=room_id)
    print(f"Player {player_name} joined {room_id}")

@socketio.on('start_game')
def on_start_game(data):
    room_id = data.get('room_id')
    room = rooms.get(room_id)
    if room and room.host_sid == request.sid:
        room.current_round = 1
        start_round(room)

def start_round(room):
    room.state = "CREATING"
    room.memes_this_round = []
    room.votes_this_round = {}
    for p in room.players.values():
        p.ready_next_round = False
        p.joined_late = False

    socketio.emit('round_started', {'round': room.current_round, 'total_rounds': room.num_rounds}, to=room.room_id)
    print(f"Room {room.room_id}: started round {room.current_round}")

@socketio.on('submit_meme')
def on_submit_meme(data):
    room_id = data.get('room_id')
    image_base64 = data.get('image')

    room = rooms.get(room_id)
    if not room or room.state != "CREATING":
        return

    room.memes_this_round.append({'sid': request.sid, 'image': image_base64})

    active_players = get_active_players(room)
    print(f"Room {room_id}: meme received {len(room.memes_this_round)}/{len(active_players)}")

    if len(room.memes_this_round) >= len(active_players):
        start_voting(room)

def start_voting(room):
    room.state = "VOTING"
    socketio.emit('start_voting', get_memes_for_voting(room), to=room.room_id)
    print(f"Room {room.room_id}: started voting")

@socketio.on('submit_vote')
def on_submit_vote(data):
    room_id = data.get('room_id')
    votes = data.get('votes')
    print(f"Received vote from {request.sid} in {room_id}: {votes}")

    room = rooms.get(room_id)
    if not room or room.state != "VOTING":
        return

    room.votes_this_round[request.sid] = votes

    if len(room.votes_this_round) >= len(get_active_players(room)):
        show_partial_results(room)

def show_partial_results(room):
    room.state = "RESULTS"

    round_scores = {p_sid: 0 for p_sid in room.players.keys()}

    for voter_sid, votes in room.votes_this_round.items():
        if isinstance(votes, dict):
            for owner_sid, score in votes.items():
                if owner_sid in round_scores and owner_sid != voter_sid:
                    try:
                        round_scores[owner_sid] += int(score)
                    except ValueError:
                        pass

    for sid, score in round_scores.items():
        if sid in room.players:
            room.players[sid].score += score

    results = [
        {'sid': p.sid, 'name': p.name, 'round_score': round_scores.get(p.sid, 0), 'total_score': p.score}
        for p in room.players.values()
    ]
    results.sort(key=lambda x: x['total_score'], reverse=True)

    socketio.emit('partial_results', {'results': results, 'round': room.current_round, 'total_rounds': room.num_rounds}, to=room.room_id)
    print(f"Room {room.room_id}: showing results round {room.current_round}")

@socketio.on('ready_next_round')
def on_ready_next_round(data):
    room_id = data.get('room_id')
    room = rooms.get(room_id)
    if not room or room.state != "RESULTS":
        return

    if request.sid in room.players:
        room.players[request.sid].ready_next_round = True

    active_players = get_active_players(room)
    ready_count = sum(1 for p in active_players if p.ready_next_round)

    if ready_count >= len(active_players):
        if room.current_round < room.num_rounds:
            room.current_round += 1
            start_round(room)
        else:
            end_game(room)

def end_game(room):
    room.state = "FINISHED"

    results = [{'sid': p.sid, 'name': p.name, 'total_score': p.score} for p in room.players.values()]
    results.sort(key=lambda x: x['total_score'], reverse=True)

    socketio.emit('game_over', {'results': results}, to=room.room_id)
    print(f"Room {room.room_id}: Game Over. Winner: {results[0]['name'] if results else 'None'}")
    rooms.pop(room.room_id, None)

@socketio.on('disconnect')
def on_disconnect():
    for room_id, room in list(rooms.items()):
        if request.sid in room.players:
            p = room.players[request.sid]
            print(f"Player {p.name} disconnected from {room_id}, waiting {RECONNECT_TIMEOUT}s to reconnect")

            # Marcar como desconectado pero mantenerle en la sala
            p.disconnected = True
            socketio.emit('player_list_update', get_player_list(room), to=room_id)

            # Avanzar el juego si estaba bloqueando alguna fase
            _check_advance(room)

            # Temporizador: si no reconecta en 30s, expulsarle definitivamente
            def expel_player(r_id, p_sid):
                room = rooms.get(r_id)
                if not room:
                    return
                player = room.players.get(p_sid)
                if not player or not player.disconnected:
                    return
                del room.players[p_sid]
                print(f"Player {player.name} expelled from {r_id} (timeout)")
                if len(room.players) == 0:
                    rooms.pop(r_id, None)
                    return
                if room.host_sid == p_sid:
                    new_host = next((s for s, pl in room.players.items() if not pl.disconnected), None)
                    if new_host:
                        room.host_sid = new_host
                        socketio.emit('host_changed', {'host_sid': new_host}, to=r_id)
                socketio.emit('player_list_update', get_player_list(room), to=r_id)
                _check_advance(room)

            p.disconnect_timer = eventlet.spawn_after(RECONNECT_TIMEOUT, expel_player, room_id, request.sid)
            break

def _check_advance(room):
    """Avanza el estado del juego si todos los jugadores activos ya han actuado."""
    active = get_active_players(room)
    if len(active) == 0:
        return
    if room.state == "CREATING":
        if len(room.memes_this_round) >= len(active):
            start_voting(room)
    elif room.state == "VOTING":
        if len(room.votes_this_round) >= len(active):
            show_partial_results(room)
    elif room.state == "RESULTS":
        ready_count = sum(1 for p in active if p.ready_next_round)
        if ready_count >= len(active):
            if room.current_round < room.num_rounds:
                room.current_round += 1
                start_round(room)
            else:
                end_game(room)

def get_active_players(room):
    """Jugadores que cuentan para el juego: no joined_late y no desconectados."""
    return [p for p in room.players.values() if not p.joined_late and not p.disconnected]

def get_player_list(room):
    return [
        {'sid': p.sid, 'name': p.name, 'score': p.score,
         'is_host': p.sid == room.host_sid, 'disconnected': p.disconnected}
        for p in room.players.values()
    ]

def get_memes_for_voting(room):
    return [{'sid': m['sid'], 'image': m['image']} for m in room.memes_this_round]

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    print(f"Starting MakeItMeme Backend Server on port {port}...")
    socketio.run(app, host='0.0.0.0', port=port)
