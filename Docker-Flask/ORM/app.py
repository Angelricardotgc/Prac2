import os
from datetime import datetime, timedelta, timezone
from functools import wraps

import jwt
from flask import Flask, request, jsonify, g
from flask_sqlalchemy import SQLAlchemy
from flask_bcrypt import Bcrypt

app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///site.db'
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['MAX_CONTENT_LENGTH'] = 20 * 1024 * 1024

SECRET_KEY = os.environ.get('SECRET_KEY', 'dev-secret-change-me')
JWT_EXP_MINUTES = int(os.environ.get('JWT_EXP_MINUTES', '60'))
ADMIN_CREATION_KEY = os.environ.get('ADMIN_CREATION_KEY', 'admin-secret-2026')

db = SQLAlchemy(app)
bcrypt = Bcrypt(app)


# ---------------- MODELOS ----------------
class User(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    password_hash = db.Column(db.String(200), nullable=False)
    role = db.Column(db.String(20), nullable=False, default='normal')


class Task(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    titulo = db.Column(db.String(120), nullable=False)
    descripcion = db.Column(db.String(500), nullable=True)

    def to_dict(self, entrega_usuario=None):
        data = {
            "id": self.id,
            "titulo": self.titulo,
            "descripcion": self.descripcion,
        }
        # Info de la entrega del usuario que está consultando (si existe)
        if entrega_usuario:
            data["entregada"] = True
            data["documento_nombre"] = entrega_usuario.documento_nombre
            data["calificacion"] = entrega_usuario.calificacion
            data["comentario_admin"] = entrega_usuario.comentario_admin
        else:
            data["entregada"] = False
            data["documento_nombre"] = None
            data["calificacion"] = None
            data["comentario_admin"] = None
        return data


class Entrega(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    task_id = db.Column(db.Integer, db.ForeignKey('task.id'), nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    documento_nombre = db.Column(db.String(200), nullable=True)
    documento_base64 = db.Column(db.Text, nullable=True)
    calificacion = db.Column(db.Float, nullable=True)
    comentario_admin = db.Column(db.String(500), nullable=True)

    def to_dict(self, incluir_documento=False):
        data = {
            "id": self.id,
            "task_id": self.task_id,
            "user_id": self.user_id,
            "documento_nombre": self.documento_nombre,
            "calificacion": self.calificacion,
            "comentario_admin": self.comentario_admin,
        }
        if incluir_documento:
            data["documento_base64"] = self.documento_base64
        return data


with app.app_context():
    db.create_all()


# ---------------- AUTENTICACIÓN (JWT) ----------------
def generar_token(user):
    payload = {
        "user_id": user.id,
        "username": user.username,
        "role": user.role,
        "exp": datetime.now(timezone.utc) + timedelta(minutes=JWT_EXP_MINUTES)
    }
    return jwt.encode(payload, SECRET_KEY, algorithm="HS256")


def token_requerido(f):
    @wraps(f)
    def decorador(*args, **kwargs):
        auth_header = request.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            return jsonify({"status": "error", "message": "Token no proporcionado"}), 401

        token = auth_header.split(' ', 1)[1]
        try:
            payload = jwt.decode(token, SECRET_KEY, algorithms=["HS256"])
            g.user_id = payload["user_id"]
            g.role = payload.get("role", "normal")
        except jwt.ExpiredSignatureError:
            return jsonify({"status": "error", "message": "Sesión expirada"}), 401
        except jwt.InvalidTokenError:
            return jsonify({"status": "error", "message": "Token inválido"}), 401

        return f(*args, **kwargs)
    return decorador


def admin_requerido(f):
    @wraps(f)
    @token_requerido
    def decorador(*args, **kwargs):
        if g.role != 'admin':
            return jsonify({"status": "error", "message": "Solo un administrador puede realizar esta acción"}), 403
        return f(*args, **kwargs)
    return decorador


# ---------------- ENDPOINTS BASE ----------------
@app.route('/', methods=['GET'])
def verificar():
    return jsonify({"status": "success", "message": "API activa"}), 200


@app.route('/register', methods=['POST'])
def register():
    data = request.get_json(silent=True) or {}
    username = data.get('username')
    password = data.get('password')
    admin_key = data.get('admin_key')

    if not username or not password:
        return jsonify({"message": "Usuario y contraseña son obligatorios"}), 400

    if User.query.filter_by(username=username).first():
        return jsonify({"message": "El usuario ya existe"}), 400

    role = 'admin' if admin_key and admin_key == ADMIN_CREATION_KEY else 'normal'

    hashed = bcrypt.generate_password_hash(password).decode('utf-8')
    nuevo_usuario = User(username=username, password_hash=hashed, role=role)
    db.session.add(nuevo_usuario)
    db.session.commit()

    return jsonify({"message": f"Usuario creado exitosamente como {role}"}), 201


@app.route('/login', methods=['POST'])
def login():
    data = request.get_json(silent=True) or {}
    username = data.get('username')
    password = data.get('password')

    usuario = User.query.filter_by(username=username).first()
    if not usuario or not bcrypt.check_password_hash(usuario.password_hash, password):
        return jsonify({"status": "error", "message": "Credenciales inválidas"}), 401

    token = generar_token(usuario)
    return jsonify({
        "status": "success",
        "message": "Login exitoso",
        "user_id": usuario.id,
        "username": usuario.username,
        "role": usuario.role,
        "token": token
    }), 200


# ---------------- TAREAS: LECTURA ----------------
# Un usuario NORMAL ve sus propias tareas con SU entrega (o sin entregar)
# Un ADMIN ve todas las tareas (sin datos de entrega individual aquí)
@app.route('/tasks', methods=['GET'])
@token_requerido
def obtener_tareas():
    tareas = Task.query.all()
    if g.role == 'admin':
        return jsonify([t.to_dict() for t in tareas]), 200
    else:
        resultado = []
        for t in tareas:
            entrega = Entrega.query.filter_by(task_id=t.id, user_id=g.user_id).first()
            resultado.append(t.to_dict(entrega_usuario=entrega))
        return jsonify(resultado), 200


@app.route('/tasks/<int:task_id>', methods=['GET'])
@token_requerido
def obtener_tarea(task_id):
    tarea = Task.query.filter_by(id=task_id).first()
    if not tarea:
        return jsonify({"message": "Tarea no encontrada"}), 404
    if g.role == 'admin':
        return jsonify(tarea.to_dict()), 200
    entrega = Entrega.query.filter_by(task_id=task_id, user_id=g.user_id).first()
    return jsonify(tarea.to_dict(entrega_usuario=entrega)), 200


# NUEVO: el admin ve TODAS las entregas de una tarea (una por cada alumno)
@app.route('/tasks/<int:task_id>/entregas', methods=['GET'])
@admin_requerido
def obtener_entregas(task_id):
    entregas = Entrega.query.filter_by(task_id=task_id).all()
    resultado = []
    for e in entregas:
        usuario = User.query.get(e.user_id)
        item = e.to_dict()
        item["username"] = usuario.username if usuario else "Desconocido"
        resultado.append(item)
    return jsonify(resultado), 200


@app.route('/entregas/<int:entrega_id>/documento', methods=['GET'])
@token_requerido
def obtener_documento(entrega_id):
    entrega = Entrega.query.filter_by(id=entrega_id).first()
    if not entrega:
        return jsonify({"message": "Entrega no encontrada"}), 404
    if g.role != 'admin' and entrega.user_id != g.user_id:
        return jsonify({"status": "error", "message": "No autorizado para ver este documento"}), 403
    return jsonify({
        "documento_nombre": entrega.documento_nombre,
        "documento_base64": entrega.documento_base64
    }), 200


# ---------------- TAREAS: CREAR / EDITAR / BORRAR (solo ADMIN) ----------------
@app.route('/tasks', methods=['POST'])
@admin_requerido
def crear_tarea():
    data = request.get_json(silent=True) or {}
    titulo = data.get('titulo')

    if not titulo:
        return jsonify({"message": "El título es obligatorio"}), 400

    tarea = Task(titulo=titulo, descripcion=data.get('descripcion', ''))
    db.session.add(tarea)
    db.session.commit()
    return jsonify(tarea.to_dict()), 201


@app.route('/tasks/<int:task_id>', methods=['PUT'])
@admin_requerido
def actualizar_tarea(task_id):
    tarea = Task.query.filter_by(id=task_id).first()
    if not tarea:
        return jsonify({"message": "Tarea no encontrada"}), 404

    data = request.get_json(silent=True) or {}
    tarea.titulo = data.get('titulo', tarea.titulo)
    tarea.descripcion = data.get('descripcion', tarea.descripcion)
    db.session.commit()

    return jsonify(tarea.to_dict()), 200


@app.route('/tasks/<int:task_id>', methods=['DELETE'])
@admin_requerido
def eliminar_tarea(task_id):
    tarea = Task.query.filter_by(id=task_id).first()
    if not tarea:
        return jsonify({"message": "Tarea no encontrada"}), 404

    Entrega.query.filter_by(task_id=task_id).delete()
    db.session.delete(tarea)
    db.session.commit()
    return jsonify({"message": "Tarea eliminada"}), 200


# ---------------- ENTREGA (usuario normal) ----------------
@app.route('/tasks/<int:task_id>/entregar', methods=['PUT'])
@token_requerido
def entregar_tarea(task_id):
    tarea = Task.query.filter_by(id=task_id).first()
    if not tarea:
        return jsonify({"message": "Tarea no encontrada"}), 404

    data = request.get_json(silent=True) or {}
    documento_nombre = data.get('documento_nombre')
    documento_base64 = data.get('documento_base64')

    if not documento_nombre or not documento_base64:
        return jsonify({"message": "Debes adjuntar un documento"}), 400

    # ¿Ya existe una entrega de ESTE usuario para ESTA tarea?
    entrega = Entrega.query.filter_by(task_id=task_id, user_id=g.user_id).first()
    if entrega is None:
        entrega = Entrega(task_id=task_id, user_id=g.user_id)
        db.session.add(entrega)

    entrega.documento_nombre = documento_nombre
    entrega.documento_base64 = documento_base64
    entrega.calificacion = None
    entrega.comentario_admin = None
    db.session.commit()

    return jsonify(tarea.to_dict(entrega_usuario=entrega)), 200


# ---------------- CALIFICAR (solo ADMIN) ----------------
@app.route('/entregas/<int:entrega_id>/calificar', methods=['PUT'])
@admin_requerido
def calificar_entrega(entrega_id):
    entrega = Entrega.query.filter_by(id=entrega_id).first()
    if not entrega:
        return jsonify({"message": "Entrega no encontrada"}), 404

    data = request.get_json(silent=True) or {}
    calificacion = data.get('calificacion')

    if calificacion is None:
        return jsonify({"message": "Debes indicar una calificación"}), 400

    entrega.calificacion = float(calificacion)
    entrega.comentario_admin = data.get('comentario_admin', '')
    db.session.commit()

    return jsonify(entrega.to_dict()), 200


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)