import os
import datetime
import sqlite3
from flask import Flask, request, jsonify, abort
from werkzeug.utils import secure_filename
from functools import wraps
from flask import request, jsonify


# --- Config ---
# UPLOAD_FOLDER = r"" 
DB_FILE = "server_api.db"
ALLOWED_EXTENSIONS = {"jpg", "jpeg", "png", "gif"}
ADMIN_API_KEY = "secret-admin-key"

app = Flask(__name__)
# app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

def require_admin_key(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        provided_key = request.headers.get("X-Admin-Key")
        if provided_key != ADMIN_API_KEY:
            return jsonify({"error": "Unauthorized"}), 401
        return f(*args, **kwargs)
    return decorated

# --- Helper functions ---
def init_db():
    """Initialize the SQLite database and create the tables if they don't exist."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("""
            CREATE TABLE IF NOT EXISTS api_keys (
                ip TEXT PRIMARY KEY,
                key TEXT NOT NULL,
                name TEXT,
                status TEXT NOT NULL CHECK(status IN ('allowed', 'pending')),
                last_request_time TEXT
            )
        """)
        
        c.execute("""
            CREATE TABLE IF NOT EXISTS request_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                ip TEXT NOT NULL,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                api_key TEXT,
                status TEXT
            )
        """)
        
        c.execute("""
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """)
        conn.commit()

def get_current_upload_folder():
    """Return the upload folder from settings, or default backup folder."""
    folder = load_setting("upload_folder", "").strip()
    if not folder:
        folder = os.path.join(os.path.dirname(os.path.abspath(__file__)), "BACKUP IMAGES FOLDER")
    if not os.path.exists(folder):
        os.makedirs(folder, exist_ok=True)
    return folder

def is_local_ip(ip):
    """Check if IP belongs to a local network range (192.x / 10.x / 172.x)."""
    return ip.startswith("192.") or ip.startswith("10.")

def allowed_file(filename):
    """Check if uploaded file has an allowed extension."""
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

def get_api_key(ip):
    """Retrieve API key and status for a device IP."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("SELECT key, status FROM api_keys WHERE ip=?", (ip,))
        return c.fetchone()

def save_api_key(ip, key, name, status="pending"):
    """Insert or update a device entry in the database."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("""
            INSERT OR REPLACE INTO api_keys (ip, key, name, status, last_request_time)
            VALUES (?, ?, ?, ?, ?)
        """, (ip, key, name, status, None))
        conn.commit()

def update_last_request(ip):
    """Update the last request timestamp for a device."""
    now = datetime.datetime.now().isoformat()
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("UPDATE api_keys SET last_request_time=? WHERE ip=?", (now, ip))
        conn.commit()


def log_request(ip, method, url, api_key, status="received"):
    """Insert a request log entry."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute(
            "INSERT INTO request_logs (timestamp, ip, method, url, api_key, status) VALUES (?, ?, ?, ?, ?, ?)",
            (datetime.datetime.now().isoformat(), ip, method, url, api_key, status)
        )
        conn.commit()

def get_logs(limit=100):
    """Retrieve the latest request logs."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("SELECT * FROM request_logs ORDER BY id DESC LIMIT ?", (limit,))
        return c.fetchall()

def clear_logs():
    """Delete all request logs."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("DELETE FROM request_logs")
        conn.commit()

def save_setting(key, value):
    """Save settings."""
    with sqlite3.connect("server_api.db") as conn:
        c = conn.cursor()
        c.execute("""
            INSERT INTO settings (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
        """, (key, value))
        conn.commit()

def load_setting(key, default=None):
    """Retrieve settings."""
    with sqlite3.connect("server_api.db") as conn:
        c = conn.cursor()
        c.execute("SELECT value FROM settings WHERE key=?", (key,))
        row = c.fetchone()
        return row[0] if row else default

# --- Admin API Routes ---
@app.before_request
def log_every_request():
    """Log every incoming request and update last request time if allowed."""
    requester_ip = request.headers.get('X-Forwarded-For', request.remote_addr).split(',')[0].strip()
    now = datetime.datetime.now()
    provided_api_key = request.headers.get('X-API-Key')
    print(f"[{now.isoformat()}] - Incoming request: {request.method} {request.url} from {requester_ip}")

    if request.path.startswith("/admin") or request.path.startswith("/static"):
        return

    log_request(requester_ip, request.method, request.url, provided_api_key, status="received")
    api_key_info = get_api_key(requester_ip)
    if api_key_info and api_key_info[1] == "allowed":
        update_last_request(requester_ip)


@app.route("/admin/logs", methods=["GET"])
@require_admin_key
def api_get_logs():
    """Return last 100 request logs."""
    logs = get_logs(100)
    return jsonify([
        {"id": l[0], "timestamp": l[1], "ip": l[2], "method": l[3],
         "url": l[4], "api_key": l[5], "status": l[6]}
        for l in logs
    ])

@app.route("/admin/logs/clear", methods=["POST"])
@require_admin_key
def api_clear_logs():
    """Clear all request logs."""
    clear_logs()
    return jsonify({"message": "Logs cleared"}), 200

@app.route('/admin/devices', methods=['GET'])
@require_admin_key
def api_get_allowed_devices():
    """Return list of allowed devices with last request time."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("SELECT ip, name, last_request_time FROM api_keys WHERE status='allowed'")
        devices = [{"ip": ip, "name": name, "last_request_time": last_request_time} for ip, name, last_request_time in c.fetchall()]
    return jsonify(devices), 200

@app.route('/admin/pending', methods=['GET'])
@require_admin_key
def api_get_pending_devices():
    """Return list of devices waiting for approval."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("SELECT ip, name FROM api_keys WHERE status='pending'")
        devices = [{"ip": ip, "name": name} for ip, name in c.fetchall()]
    return jsonify(devices), 200

@app.route('/admin/update_name', methods=['POST'])
@require_admin_key
def api_update_device_name():
    """Update the name of a registered device."""
    data = request.json
    ip = data.get("ip")
    new_name = data.get("name")
    if not ip or not new_name:
        return jsonify({"error": "IP and name required"}), 400
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("UPDATE api_keys SET name=? WHERE ip=?", (new_name, ip))
        conn.commit()
    return jsonify({"message": "Name updated"}), 200

@app.route('/admin/allow_device', methods=['POST'])
@require_admin_key
def api_allow_device():
    """Approve a pending device and mark it as allowed."""
    data = request.json
    ip = data.get("ip")
    if not ip:
        return jsonify({"error": "IP required"}), 400
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("UPDATE api_keys SET status='allowed' WHERE ip=?", (ip,))
        conn.commit()
    return jsonify({"message": "Device allowed"}), 200

@app.route('/admin/deny_device', methods=['POST'])
@require_admin_key
def api_deny_device():
    """Deny (remove) a device from the database."""
    data = request.json
    ip = data.get("ip")
    if not ip:
        return jsonify({"error": "IP required"}), 400
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("DELETE FROM api_keys WHERE ip=?", (ip,))
        conn.commit()
    return jsonify({"message": "Device denied"}), 200

@app.route("/admin/settings", methods=["GET"])
@require_admin_key
def api_get_settings():
    """Return all settings from the database."""
    with sqlite3.connect(DB_FILE) as conn:
        c = conn.cursor()
        c.execute("SELECT key, value FROM settings")
        rows = c.fetchall()
    return jsonify({key: value for key, value in rows}), 200

@app.route("/admin/settings", methods=["POST"])
def update_settings():
    """Update settings in the database"""
    provided_key = request.headers.get("X-Admin-Key")
    if provided_key != ADMIN_API_KEY:
        return jsonify({"error": "Unauthorized"}), 403

    data = request.get_json()
    upload_folder = data.get("upload_folder", "").strip()

    if not upload_folder:
        upload_folder = ''

    with sqlite3.connect(DB_FILE) as conn:
        conn.execute("""
            INSERT INTO settings (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
        """, ("upload_folder", upload_folder))
        conn.commit()

    global UPLOAD_FOLDER
    UPLOAD_FOLDER = upload_folder

    return jsonify({"message": "Settings updated", "upload_folder": upload_folder})

# --- Public API Routes ---
@app.route('/check_status', methods=['POST'])
def check_status():
    """Check if a device is allowed and has a valid API key."""
    requester_ip = request.headers.get('X-Forwarded-For', request.remote_addr).split(',')[0].strip()
    provided_api_key = (request.headers.get('X-API-Key') or "").strip()

    if not is_local_ip(requester_ip):
        abort(403, description="Forbidden: Client IP not local.")

    key_info = get_api_key(requester_ip)
    if not key_info:
        abort(401, description="Unauthorized: Device not registered.")

    stored_key, status = key_info
    if status != "allowed":
        abort(401, description="Unauthorized: Device not allowed.")

    if provided_api_key != stored_key:
        abort(401, description="Unauthorized: Invalid API key.")

    return jsonify({"status": "approved"}), 200


# --- Updated upload_image route ---
@app.route('/upload_image', methods=['POST'])
def upload_image():
    """Upload an image to the server after validating API key and IP."""
    requester_ip = request.headers.get('X-Forwarded-For', request.remote_addr).split(',')[0].strip()
    provided_api_key = request.headers.get('X-API-Key')

    if not is_local_ip(requester_ip):
        abort(403, description="Forbidden: Client IP not local.")

    key_info = get_api_key(requester_ip)
    if not key_info or key_info[1] != "allowed":
        abort(401, description="Unauthorized: Device not allowed.")

    stored_key = key_info[0]
    if provided_api_key != stored_key:
        abort(401, description="Unauthorized: Invalid API key.")

    now = datetime.datetime.now()
    datetime_str = now.strftime("%Y%m%d_%H%M")

    photo_name = request.headers.get('X-Photo-Name')
    if not photo_name or not request.data:
        abort(400, description="Bad Request: Missing photo name or data.")

    filename_base = secure_filename(photo_name)
    if not allowed_file(filename_base):
        abort(400, description="Bad Request: File type not allowed.")

    base_upload_folder = get_current_upload_folder()

    today_folder = now.strftime("%Y-%m-%d")
    daily_upload_folder = os.path.join(base_upload_folder, today_folder)
    os.makedirs(daily_upload_folder, exist_ok=True)

    base_name, ext = os.path.splitext(filename_base)
    final_folder = os.path.join(daily_upload_folder, base_name)
    os.makedirs(final_folder, exist_ok=True)

    final_filename = f"{base_name.upper()}_{datetime_str}{ext}"
    counter = 1
    while os.path.exists(os.path.join(final_folder, final_filename)):
        final_filename = f"{base_name.upper()}_{datetime_str} ({counter}){ext}"
        counter += 1

    filepath = os.path.join(final_folder, final_filename)
    with open(filepath, 'wb') as f:
        f.write(request.data)

    update_last_request(requester_ip)
    return jsonify({"message": f"Photo uploaded successfully as {final_filename}"}), 200


@app.route('/register_key', methods=['POST'])
def register_key():
    """Register a new device (saved as pending until approved)."""
    requester_ip = request.headers.get('X-Forwarded-For', request.remote_addr).split(',')[0].strip()
    data = request.json or {}
    key = data.get('key')
    device_name = data.get('device_name', 'Unnamed Device')

    if not is_local_ip(requester_ip):
        abort(403, description="Forbidden: Client IP not local.")
    if not key:
        abort(400, description="Bad Request: Missing 'key'.")

    key_info = get_api_key(requester_ip)
    if key_info:
        stored_key, status = key_info
        if status == "allowed":
            return jsonify({"message": "Device IP is already allowed."}), 200
        elif status == "pending":
            return jsonify({"message": "Device IP is already pending approval."}), 200

    save_api_key(requester_ip, key, device_name, status="pending")
    return jsonify({"message": "Key submitted for approval."}), 202


# --- Main Entry ---
if __name__ == "__main__":
    # os.makedirs(UPLOAD_FOLDER, exist_ok=True)   # Ensure upload folder exists
    init_db()                                   # Initialize database
    app.run(host="0.0.0.0", port=6868, debug=True)  # Run Flask app on all interfaces
