import sys
import os
import json
import requests
from PyQt5 import QtWidgets, QtCore

# Settings file location
SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings.json")

# Default API Base
API_BASE = "http://127.0.0.1:6868/admin"
ADMIN_KEY = "your-secret-admin-key"
HEADERS = {"X-Admin-Key": ADMIN_KEY}

# Global variable for upload folder (from backend)
UPLOAD_FOLDER = ""


def load_local_settings():
    defaults = {"use_default_api": True, "server_ip": "127.0.0.1", "server_port": "6868"}
    if not os.path.exists(SETTINGS_FILE):
        save_local_settings(defaults)
        return defaults
    try:
        with open(SETTINGS_FILE, "r") as f:
            return json.load(f)
    except Exception:
        return defaults


def save_local_settings(settings):
    try:
        with open(SETTINGS_FILE, "w") as f:
            json.dump(settings, f, indent=4)
    except Exception as e:
        print("Error saving local settings:", e)


# --- Utility: filter rows of QTableWidget based on search text ---
def filter_table(table, text):
    text = text.lower()
    for row in range(table.rowCount()):
        match = False
        for col in range(table.columnCount()):
            item = table.item(row, col)
            if item and text in item.text().lower():
                match = True
                break
        table.setRowHidden(row, not match)


class DeviceManager(QtWidgets.QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Device Manager")
        self.setGeometry(100, 100, 900, 600)

        self.tabs = QtWidgets.QTabWidget()
        self.setCentralWidget(self.tabs)

        self.setup_devices_tab()
        self.setup_pending_tab()
        self.setup_logs_tab()
        self.setup_settings_tab()

        # Auto-refresh logs every 10 seconds
        self.log_timer = QtCore.QTimer()
        self.log_timer.timeout.connect(self.refresh_logs)
        self.log_timer.start(10000)

        # Load settings initially
        self.load_settings()
        self.refresh_tables()
        self.refresh_logs()

    # --- Devices Tab ---
    def setup_devices_tab(self):
        self.devices_tab = QtWidgets.QWidget()
        self.devices_layout = QtWidgets.QVBoxLayout()

        top_layout = QtWidgets.QHBoxLayout()
        self.refresh_devices_btn = QtWidgets.QPushButton("Refresh Devices")
        self.refresh_devices_btn.clicked.connect(self.refresh_tables)
        self.search_devices = QtWidgets.QLineEdit()
        self.search_devices.setPlaceholderText("Search devices...")
        self.search_devices.textChanged.connect(lambda text: filter_table(self.devices_table, text))
        top_layout.addWidget(self.refresh_devices_btn)
        top_layout.addWidget(self.search_devices)

        self.devices_layout.addLayout(top_layout)

        self.devices_table = QtWidgets.QTableWidget()
        self.devices_table.setColumnCount(4)
        self.devices_table.setHorizontalHeaderLabels(["IP", "Name", "Last Request", "Actions"])
        self.devices_table.setEditTriggers(QtWidgets.QAbstractItemView.NoEditTriggers)
        self.devices_table.setSortingEnabled(True)
        self.devices_layout.addWidget(self.devices_table)

        self.devices_tab.setLayout(self.devices_layout)
        self.tabs.addTab(self.devices_tab, "Devices")
        self.devices_table.cellDoubleClicked.connect(self.edit_name_dialog)

    # --- Pending Devices Tab ---
    def setup_pending_tab(self):
        self.pending_tab = QtWidgets.QWidget()
        self.pending_layout = QtWidgets.QVBoxLayout()

        top_layout = QtWidgets.QHBoxLayout()
        self.refresh_pending_btn = QtWidgets.QPushButton("Refresh Pending Devices")
        self.refresh_pending_btn.clicked.connect(self.refresh_tables)
        self.search_pending = QtWidgets.QLineEdit()
        self.search_pending.setPlaceholderText("Search pending devices...")
        self.search_pending.textChanged.connect(lambda text: filter_table(self.pending_table, text))
        top_layout.addWidget(self.refresh_pending_btn)
        top_layout.addWidget(self.search_pending)

        self.pending_layout.addLayout(top_layout)

        self.pending_table = QtWidgets.QTableWidget()
        self.pending_table.setColumnCount(3)
        self.pending_table.setHorizontalHeaderLabels(["IP", "Name", "Actions"])
        self.pending_table.setSortingEnabled(True)
        self.pending_layout.addWidget(self.pending_table)

        self.pending_tab.setLayout(self.pending_layout)
        self.tabs.addTab(self.pending_tab, "Pending Devices")

    # --- Logs Tab ---
    def setup_logs_tab(self):
        self.logs_tab = QtWidgets.QWidget()
        self.logs_layout = QtWidgets.QVBoxLayout()

        top_layout = QtWidgets.QHBoxLayout()
        self.refresh_logs_btn = QtWidgets.QPushButton("Refresh Logs")
        self.refresh_logs_btn.clicked.connect(self.refresh_logs)
        self.clear_logs_btn = QtWidgets.QPushButton("Clear Logs")
        self.clear_logs_btn.clicked.connect(self.clear_logs)
        self.search_logs = QtWidgets.QLineEdit()
        self.search_logs.setPlaceholderText("Search logs...")
        self.search_logs.textChanged.connect(lambda text: filter_table(self.logs_table, text))

        top_layout.addWidget(self.refresh_logs_btn)
        top_layout.addWidget(self.clear_logs_btn)
        top_layout.addWidget(self.search_logs)
        self.logs_layout.addLayout(top_layout)

        self.logs_table = QtWidgets.QTableWidget()
        self.logs_table.setColumnCount(6)
        self.logs_table.setHorizontalHeaderLabels(["Timestamp", "IP", "Method", "URL", "API Key", "Status"])
        self.logs_table.setSortingEnabled(True)
        self.logs_layout.addWidget(self.logs_table)

        self.logs_tab.setLayout(self.logs_layout)
        self.tabs.addTab(self.logs_tab, "Logs")

    # --- Settings Tab ---
    def setup_settings_tab(self):
        self.settings_tab = QtWidgets.QWidget()
        self.settings_layout = QtWidgets.QFormLayout()

        # Use default API checkbox
        self.use_default_checkbox = QtWidgets.QCheckBox("Use Default API Base (127.0.0.1:6868)")
        self.use_default_checkbox.stateChanged.connect(self.toggle_default_api)
        self.settings_layout.addRow(self.use_default_checkbox)

        # Server IP input
        self.server_ip_input = QtWidgets.QLineEdit()
        self.settings_layout.addRow("Server IP:", self.server_ip_input)

        # Port input
        self.server_port_input = QtWidgets.QLineEdit()
        self.settings_layout.addRow("Port:", self.server_port_input)

        # Upload folder picker
        self.upload_folder_input = QtWidgets.QLineEdit()
        self.upload_folder_btn = QtWidgets.QPushButton("Browse")
        self.upload_folder_btn.clicked.connect(self.select_upload_folder)
        folder_layout = QtWidgets.QHBoxLayout()
        folder_layout.addWidget(self.upload_folder_input)
        folder_layout.addWidget(self.upload_folder_btn)
        self.settings_layout.addRow("Upload Folder:", folder_layout)

        # Save button
        self.save_settings_btn = QtWidgets.QPushButton("Save Settings")
        self.save_settings_btn.clicked.connect(self.save_settings)
        self.settings_layout.addRow(self.save_settings_btn)

        self.settings_tab.setLayout(self.settings_layout)
        self.tabs.addTab(self.settings_tab, "Settings")

    def select_upload_folder(self):
        folder = QtWidgets.QFileDialog.getExistingDirectory(self, "Select Upload Folder")
        if folder:
            self.upload_folder_input.setText(folder)

    def toggle_default_api(self):
        use_default = self.use_default_checkbox.isChecked()
        self.server_ip_input.setDisabled(use_default)
        self.server_port_input.setDisabled(use_default)

    # --- Settings Load/Save ---
    def load_settings(self):
        global API_BASE, UPLOAD_FOLDER
        local_settings = load_local_settings()

        use_default = local_settings.get("use_default_api", True)
        self.use_default_checkbox.setChecked(use_default)
        self.toggle_default_api()

        if use_default:
            API_BASE = "http://127.0.0.1:6868/admin"
        else:
            server_ip = local_settings.get("server_ip", "127.0.0.1")
            server_port = local_settings.get("server_port", "6868")
            self.server_ip_input.setText(server_ip)
            self.server_port_input.setText(server_port)
            API_BASE = f"http://{server_ip}:{server_port}/admin"

        try:
            r = requests.get(f"{API_BASE}/settings", headers=HEADERS)
            r.raise_for_status()
            settings = r.json()
            UPLOAD_FOLDER = settings.get("upload_folder", "")
            self.upload_folder_input.setText(UPLOAD_FOLDER)
        except Exception as e:
            print("Error loading upload folder from backend:", e)

    def save_settings(self):
        global API_BASE, UPLOAD_FOLDER
        use_default = self.use_default_checkbox.isChecked()

        local_data = {"use_default_api": use_default}

        if not use_default:
            server_ip = self.server_ip_input.text().strip()
            server_port = self.server_port_input.text().strip()
            if not server_ip or not server_port:
                QtWidgets.QMessageBox.warning(self, "Invalid Input", "IP and Port cannot be empty.")
                return
            local_data["server_ip"] = server_ip
            local_data["server_port"] = server_port
            API_BASE = f"http://{server_ip}:{server_port}/admin"
        else:
            API_BASE = "http://127.0.0.1:6868/admin"

        save_local_settings(local_data)

        # Upload folder still goes to backend
        UPLOAD_FOLDER = self.upload_folder_input.text().strip()
        data = {"upload_folder": UPLOAD_FOLDER}

        try:
            r = requests.post(f"{API_BASE}/settings", headers=HEADERS, json=data)
            r.raise_for_status()
            QtWidgets.QMessageBox.information(self, "Success", "Settings saved successfully.")
        except Exception as e:
            QtWidgets.QMessageBox.critical(self, "Error", f"Failed to save settings:\n{e}")

        self.refresh_tables()
        self.refresh_logs()

    # --- Refresh Tables & Logs ---
    def refresh_tables(self):
        try:
            r = requests.get(f"{API_BASE}/devices", headers=HEADERS)
            r.raise_for_status()
            devices = r.json()
        except Exception as e:
            devices = []
            print("Error fetching devices:", e)

        self.devices_table.setRowCount(len(devices))
        for row, device in enumerate(devices):
            self.devices_table.setItem(row, 0, QtWidgets.QTableWidgetItem(device["ip"]))
            self.devices_table.setItem(row, 1, QtWidgets.QTableWidgetItem(device["name"]))
            self.devices_table.setItem(row, 2, QtWidgets.QTableWidgetItem(device.get("last_request_time", "Never")))

            delete_btn = QtWidgets.QPushButton("Delete")
            delete_btn.clicked.connect(lambda _, ip=device["ip"]: self.delete_device(ip))
            container = QtWidgets.QWidget()
            layout = QtWidgets.QHBoxLayout()
            layout.addWidget(delete_btn)
            layout.setContentsMargins(0, 0, 0, 0)
            container.setLayout(layout)
            self.devices_table.setCellWidget(row, 3, container)

        try:
            r = requests.get(f"{API_BASE}/pending", headers=HEADERS)
            r.raise_for_status()
            pending = r.json()
        except Exception as e:
            pending = []
            print("Error fetching pending devices:", e)

        self.pending_table.setRowCount(len(pending))
        for row, device in enumerate(pending):
            self.pending_table.setItem(row, 0, QtWidgets.QTableWidgetItem(device["ip"]))
            self.pending_table.setItem(row, 1, QtWidgets.QTableWidgetItem(device["name"]))

            allow_btn = QtWidgets.QPushButton("Allow")
            deny_btn = QtWidgets.QPushButton("Deny")
            allow_btn.clicked.connect(lambda _, ip=device["ip"]: self.allow_device(ip))
            deny_btn.clicked.connect(lambda _, ip=device["ip"]: self.deny_device(ip))
            container = QtWidgets.QWidget()
            layout = QtWidgets.QHBoxLayout()
            layout.addWidget(allow_btn)
            layout.addWidget(deny_btn)
            layout.setContentsMargins(0, 0, 0, 0)
            container.setLayout(layout)
            self.pending_table.setCellWidget(row, 2, container)

    def refresh_logs(self):
        try:
            r = requests.get(f"{API_BASE}/logs", headers=HEADERS)
            r.raise_for_status()
            logs = r.json()
        except Exception as e:
            logs = []
            print("Error fetching logs:", e)

        self.logs_table.setRowCount(len(logs))
        for row, log in enumerate(logs):
            self.logs_table.setItem(row, 0, QtWidgets.QTableWidgetItem(log["timestamp"]))
            self.logs_table.setItem(row, 1, QtWidgets.QTableWidgetItem(log["ip"]))
            self.logs_table.setItem(row, 2, QtWidgets.QTableWidgetItem(log["method"]))
            self.logs_table.setItem(row, 3, QtWidgets.QTableWidgetItem(log["url"]))
            self.logs_table.setItem(row, 4, QtWidgets.QTableWidgetItem(log["api_key"] or ""))
            self.logs_table.setItem(row, 5, QtWidgets.QTableWidgetItem(log["status"]))

    def clear_logs(self):
        try:
            r = requests.post(f"{API_BASE}/logs/clear", headers=HEADERS)
            r.raise_for_status()
        except Exception as e:
            print("Error clearing logs:", e)
        self.refresh_logs()

    # --- Device Actions ---
    def edit_name_dialog(self, row, column):
        if column != 1:
            return
        ip = self.devices_table.item(row, 0).text()
        old_name = self.devices_table.item(row, 1).text()
        new_name, ok = QtWidgets.QInputDialog.getText(self, "Edit Device Name", f"Change name for {ip}:", text=old_name)
        if ok and new_name.strip():
            try:
                r = requests.post(f"{API_BASE}/update_name", json={"ip": ip, "name": new_name.strip()}, headers=HEADERS)
                r.raise_for_status()
            except Exception as e:
                print("Error updating name:", e)
            self.refresh_tables()

    def delete_device(self, ip):
        confirm = QtWidgets.QMessageBox.question(self, "Delete Device", f"Are you sure you want to delete {ip}?",
                                                 QtWidgets.QMessageBox.Yes | QtWidgets.QMessageBox.No)
        if confirm == QtWidgets.QMessageBox.Yes:
            try:
                r = requests.post(f"{API_BASE}/deny_device", json={"ip": ip}, headers=HEADERS)
                r.raise_for_status()
            except Exception as e:
                print("Error deleting device:", e)
            self.refresh_tables()

    def allow_device(self, ip):
        try:
            r = requests.post(f"{API_BASE}/allow_device", json={"ip": ip}, headers=HEADERS)
            r.raise_for_status()
        except Exception as e:
            print("Error allowing device:", e)
        self.refresh_tables()

    def deny_device(self, ip):
        try:
            r = requests.post(f"{API_BASE}/deny_device", json={"ip": ip}, headers=HEADERS)
            r.raise_for_status()
        except Exception as e:
            print("Error denying device:", e)
        self.refresh_tables()


# --- Run Application ---
if __name__ == "__main__":
    app = QtWidgets.QApplication(sys.argv)
    window = DeviceManager()
    window.show()
    sys.exit(app.exec_())
