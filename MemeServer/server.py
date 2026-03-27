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

class Room:
    def __init__(self, room_id, host_sid, num_rounds):
        self.room_id = room_id
        self.host_sid = host_sid
        self.num_rounds = num_rounds
        self.current_round = 0
        self.state = "LOBBY" # LOBBY, CREATING, VOTING, RESULTS, FINISHED
        self.players = {} # sid -> Player
        self.memes_this_round = [] # {sid, image_data}
        self.votes_this_round = {} # voter_sid -> {meme_owner_sid: score}

class Player:
    def __init__(self, sid, name):
        self.sid = sid
        self.name = name
        self.score = 0
        self.ready_next_round = False
        self.joined_late = False

@app.route('/')
def index():
    return "MakeItMeme Backend Running"

@socketio.on('create_room')
def on_create_room(data):
    player_name = data.get('name', 'Host')
    # Fix: validate num_rounds to prevent invalid values
    num_rounds = max(1, min(10, int(data.get('num_rounds', 3))))

    # Fix: check for collision before assigning room ID
    room_id = str(uuid.uuid4())[:6].upper()
    while room_id in rooms:
        room_id = str(uuid.uuid4())[:6].upper()

    join_room(room_id)

    rooms[room_id] = Room(room_id, request.sid, num_rounds)
    player = Player(request.sid, player_name)
    rooms[room_id].players[request.sid] = player

    emit('room_created', {'room_id': room_id, 'player_id': request.sid})
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
    join_room(room_id)

    player = Player(request.sid, player_name)
    if room.state != "LOBBY":
        player.joined_late = True

    room.players[request.sid] = player

    emit('joined_room', {'room_id': room_id, 'player_id': request.sid, 'state': room.state})
    emit('player_list_update', get_player_list(room), to=room_id)
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

    emit('round_started', {'round': room.current_round, 'total_rounds': room.num_rounds}, to=room.room_id)
    print(f"Room {room.room_id}: started round {room.current_round}")

@socketio.on('submit_meme')
def on_submit_meme(data):
    room_id = data.get('room_id')
    image_base64 = data.get('image')

    room = rooms.get(room_id)
    if not room or room.state != "CREATING":
        return

    room.memes_this_round.append({
        'sid': request.sid,
        'image': image_base64
    })

    active_players = [p for p in room.players.values() if not p.joined_late]
    print(f"Room {room_id}: User {request.sid} submitted meme. Received {len(room.memes_this_round)} / {len(active_players)}")

    if len(room.memes_this_round) >= len(active_players):
        start_voting(room)

def start_voting(room):
    room.state = "VOTING"
    emit('start_voting', get_memes_for_voting(room), to=room.room_id)
    print(f"Room {room.room_id}: started voting")

@socketio.on('submit_vote')
def on_submit_vote(data):
    room_id = data.get('room_id')
    votes = data.get('votes') # dict: {meme_owner_sid: score (1-10)}
    print(f"Received vote from {request.sid} in {room_id}: {votes}")

    room = rooms.get(room_id)
    if not room or room.state != "VOTING":
        return

    room.votes_this_round[request.sid] = votes

    active_players = [p for p in room.players.values() if not p.joined_late]

    if len(room.votes_this_round) >= len(active_players):
        show_partial_results(room)

def show_partial_results(room):
    room.state = "RESULTS"

    round_scores = {}
    for p_sid in room.players.keys():
        round_scores[p_sid] = 0

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

    results = []
    for p in room.players.values():
        results.append({
            'sid': p.sid,
            'name': p.name,
            'round_score': round_scores.get(p.sid, 0),
            'total_score': p.score
        })

    results.sort(key=lambda x: x['total_score'], reverse=True)

    emit('partial_results', {'results': results, 'round': room.current_round, 'total_rounds': room.num_rounds}, to=room.room_id)
    print(f"Room {room.room_id}: showing results for round {room.current_round}")

@socketio.on('ready_next_round')
def on_ready_next_round(data):
    room_id = data.get('room_id')
    room = rooms.get(room_id)
    if not room or room.state != "RESULTS":
        return

    if request.sid in room.players:
        room.players[request.sid].ready_next_round = True

    active_players = [p for p in room.players.values() if not p.joined_late]
    ready_count = sum(1 for p in active_players if p.ready_next_round)

    if ready_count >= len(active_players):
        if room.current_round < room.num_rounds:
            room.current_round += 1
            start_round(room)
        else:
            end_game(room)

def end_game(room):
    room.state = "FINISHED"

    results = []
    for p in room.players.values():
        results.append({
            'sid': p.sid,
            'name': p.name,
            'total_score': p.score
        })
    results.sort(key=lambda x: x['total_score'], reverse=True)

    emit('game_over', {'results': results}, to=room.room_id)
    print(f"Room {room.room_id}: Game Over. Winner: {results[0]['name'] if results else 'None'}")
    # Fix: remove finished room from memory
    rooms.pop(room.room_id, None)

@socketio.on('disconnect')
def on_disconnect():
    for room_id, room in list(rooms.items()):
        if request.sid in room.players:
            p = room.players[request.sid]
            del room.players[request.sid]
            print(f"Player {p.name} disconnected from {room_id}")

            if len(room.players) == 0:
                del rooms[room_id]
                print(f"Room {room_id} deleted (empty)")
            else:
                if room.host_sid == request.sid:
                    new_host_sid = list(room.players.keys())[0]
                    room.host_sid = new_host_sid
                    emit('host_changed', {'host_sid': new_host_sid}, to=room_id)
                emit('player_list_update', get_player_list(room), to=room_id)

                # Fix: advance game state if it was waiting on the disconnected player
                active_players = [pl for pl in room.players.values() if not pl.joined_late]
                if len(active_players) > 0:
                    if room.state == "CREATING":
                        if len(room.memes_this_round) >= len(active_players):
                            start_voting(room)
                    elif room.state == "VOTING":
                        if len(room.votes_this_round) >= len(active_players):
                            show_partial_results(room)
                    elif room.state == "RESULTS":
                        ready_count = sum(1 for pl in active_players if pl.ready_next_round)
                        if ready_count >= len(active_players):
                            if room.current_round < room.num_rounds:
                                room.current_round += 1
                                start_round(room)
                            else:
                                end_game(room)
            break

def get_player_list(room):
    return [{'sid': p.sid, 'name': p.name, 'score': p.score, 'is_host': p.sid == room.host_sid} for p in room.players.values()]

def get_memes_for_voting(room):
    return [{'sid': m['sid'], 'image': m['image']} for m in room.memes_this_round]

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    print(f"Starting MakeItMeme Backend Server on port {port}...")
    socketio.run(app, host='0.0.0.0', port=port)
