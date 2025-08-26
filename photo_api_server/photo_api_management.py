import sys
import os
import json
import requests
from PyQt5 import QtWidgets, QtCore

# Settings file location
SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings_api.json")

def load_local_settings():
    defaults = {"use_default_api": True, "server_ip": "127.0.0.1", "server_port": "6868"}
    if not os.path.exists(SETTINGS_FILE):
        save_local_settings(defaults)
        return defaults
    try:
        with open(SETTINGS_FILE, "r") as f:
            settings = json.load(f)
            # Validate required fields
            if not isinstance(settings.get("use_default_api"), bool):
                settings["use_default_api"] = defaults["use_default_api"]
            if not settings.get("server_ip"):
                settings["server_ip"] = defaults["server_ip"]
            if not settings.get("server_port"):
                settings["server_port"] = defaults["server_port"]
            return settings
    except Exception as e:
        print(f"Error loading settings_api.json: {e}")
        save_local_settings(defaults)
        return defaults

def save_local_settings(settings):
    try:
        with open(SETTINGS_FILE, "w") as f:
            json.dump(settings, f, indent=4)
    except Exception as e:
        print("Error saving local settings:", e)

# Initialize API_BASE using settings from JSON file or default
def initialize_api_base():
    settings = load_local_settings()
    if settings.get("use_default_api", True):
        api_base = "http://127.0.0.1:6868/admin"
    else:
        server_ip = settings.get("server_ip", "127.0.0.1")
        server_port = settings.get("server_port", "6868")
        api_base = f"http://{server_ip}:{server_port}/admin"
    print(f"Initialized API_BASE to {api_base}")
    return api_base

# Global variables
API_BASE = initialize_api_base()
ADMIN_KEY = "b4a8f4f1d1d21a8b0e4b39a5827f3a6c7d1d7d43a6f1e7c4e3e2b19a53b7c9fa"
HEADERS = {"X-Admin-Key": ADMIN_KEY}
UPLOAD_FOLDER = ""

# Network Worker for async requests
class NetworkWorker(QtCore.QThread):
    result = QtCore.pyqtSignal(object, str, bool)  # data, error_msg, success

    def __init__(self, method, url, headers, json_data=None, timeout=2):
        super().__init__()
        self.method = method
        self.url = url
        self.headers = headers
        self.json_data = json_data
        self.timeout = timeout

    def run(self):
        try:
            if self.method == "GET":
                r = requests.get(self.url, headers=self.headers, timeout=self.timeout)
            elif self.method == "POST":
                r = requests.post(self.url, headers=self.headers, json=self.json_data, timeout=self.timeout)
            r.raise_for_status()
            self.result.emit(r.json(), "", True)
        except Exception as e:
            self.result.emit(None, str(e), False)

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

class ResizableTableWidget(QtWidgets.QTableWidget):
    def __init__(self, parent=None, column_ratios=None):
        super().__init__(parent)
        self.column_ratios = column_ratios or []
        self.horizontalHeader().setSectionResizeMode(QtWidgets.QHeaderView.Interactive)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        total_width = self.viewport().width()
        for i, ratio in enumerate(self.column_ratios):
            self.setColumnWidth(i, int(total_width * ratio))

class DeviceManager(QtWidgets.QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Device Manager")
        self.setGeometry(100, 100, 900, 600)
        self.active_threads = []  # Track active threads
        self.devices_thread_running = False
        self.pending_thread_running = False
        self.logs_thread_running = False
        self.settings_thread_running = False
        self.current_tab = 0  # Track current tab index

        # Main vertical layout
        self.main_layout = QtWidgets.QVBoxLayout()
        self.tabs = QtWidgets.QTabWidget()
        self.tabs.currentChanged.connect(self.on_tab_changed)  # Connect tab change signal
        self.main_layout.addWidget(self.tabs)

        self.setup_devices_tab()
        self.setup_pending_tab()
        self.setup_logs_tab()
        self.setup_settings_tab()

        # --- Server Status Indicator and Loading Spinner at the bottom ---
        status_layout = QtWidgets.QHBoxLayout()
        status_layout.setContentsMargins(10, 5, 10, 5)

        self.status_label = QtWidgets.QLabel("●")
        self.status_label.setStyleSheet("color: gray; font-size: 20px;")
        self.status_text = QtWidgets.QLabel("Checking...")
        self.status_text.setStyleSheet("font-size: 14px;")
        self.loading_spinner = QtWidgets.QProgressBar()
        self.loading_spinner.setFixedWidth(100)
        self.loading_spinner.setRange(0, 0)  # Indeterminate mode for spinning animation
        self.loading_spinner.hide()  # Hidden by default

        status_layout.addWidget(self.status_label)
        status_layout.addWidget(self.status_text)
        status_layout.addWidget(self.loading_spinner)
        status_layout.addStretch()

        self.main_layout.addLayout(status_layout)

        # Set main layout to a central widget
        container = QtWidgets.QWidget()
        container.setLayout(self.main_layout)
        self.setCentralWidget(container)

        # Timers
        self.status_timer = QtCore.QTimer()
        self.status_timer.timeout.connect(self.check_server_status)
        self.status_timer.start(5000)

        # Load settings & initial refresh
        print("Initializing settings and triggering initial refresh")
        self.load_settings()
        print("Calling on_tab_changed(0) for initial refresh")
        self.on_tab_changed(0)  # Trigger initial refresh for the first tab

    def closeEvent(self, event):
        # Wait for all threads to finish before closing
        for thread in self.active_threads:
            if thread.isRunning():
                thread.quit()
                thread.wait()
        event.accept()

    def on_tab_changed(self, index):
        print(f"Tab changed to index {index}")
        self.current_tab = index
        if index in (0, 1):  # Devices or Pending Devices tab
            try:
                self.refresh_tables()
            except Exception as e:
                print(f"Error refreshing tables: {e}")
        elif index == 2:  # Logs tab
            try:
                self.refresh_logs()
            except Exception as e:
                print(f"Error refreshing logs: {e}")
        elif index == 3:  # Settings tab
            try:
                self.load_settings()
            except Exception as e:
                print(f"Error loading settings: {e}")

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

        self.devices_table = ResizableTableWidget(column_ratios=[0.2, 0.3, 0.3, 0.2])
        self.devices_table.setColumnCount(4)
        self.devices_table.setHorizontalHeaderLabels(["IP", "Name", "Last Request", "Actions"])
        self.devices_table.setEditTriggers(QtWidgets.QAbstractItemView.NoEditTriggers)
        self.devices_table.setSortingEnabled(True)
        self.devices_layout.addWidget(self.devices_table)

        header = self.devices_table.horizontalHeader()
        header.setSectionResizeMode(QtWidgets.QHeaderView.Stretch)
        self.devices_table.horizontalHeader().setSectionResizeMode(QtWidgets.QHeaderView.Interactive)
        self.devices_table.horizontalHeader().setStretchLastSection(True)

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

        self.pending_table = ResizableTableWidget(column_ratios=[0.3, 0.4, 0.3])
        self.pending_table.setColumnCount(3)
        self.pending_table.setHorizontalHeaderLabels(["IP", "Name", "Actions"])
        self.pending_table.setSortingEnabled(True)
        self.pending_layout.addWidget(self.pending_table)

        header = self.pending_table.horizontalHeader()
        header.setSectionResizeMode(QtWidgets.QHeaderView.Stretch)
        self.pending_table.horizontalHeader().setSectionResizeMode(QtWidgets.QHeaderView.Interactive)
        self.pending_table.horizontalHeader().setStretchLastSection(True)

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

        self.logs_table = ResizableTableWidget(column_ratios=[0.2, 0.2, 0.1, 0.4, 0.1])
        self.logs_table.setColumnCount(5)
        self.logs_table.setHorizontalHeaderLabels(["Timestamp", "IP", "Method", "URL", "Status"])
        self.logs_table.setSortingEnabled(True)

        header = self.logs_table.horizontalHeader()
        header.setSectionResizeMode(QtWidgets.QHeaderView.Stretch)
        self.logs_table.horizontalHeader().setSectionResizeMode(QtWidgets.QHeaderView.Interactive)
        self.logs_table.horizontalHeader().setStretchLastSection(True)

        self.logs_layout.addWidget(self.logs_table)

        self.logs_tab.setLayout(self.logs_layout)
        self.tabs.addTab(self.logs_tab, "Logs")

    # --- Settings Tab ---
    def setup_settings_tab(self):
        self.settings_tab = QtWidgets.QWidget()
        self.settings_layout = QtWidgets.QFormLayout()

        self.use_default_checkbox = QtWidgets.QCheckBox("Use Default API Base (127.0.0.1:6868)")
        self.use_default_checkbox.stateChanged.connect(self.toggle_default_api)
        self.settings_layout.addRow(self.use_default_checkbox)

        self.server_ip_input = QtWidgets.QLineEdit()
        self.settings_layout.addRow("Server IP:", self.server_ip_input)

        self.server_port_input = QtWidgets.QLineEdit()
        self.settings_layout.addRow("Port:", self.server_port_input)

        self.upload_folder_input = QtWidgets.QLineEdit()
        self.upload_folder_btn = QtWidgets.QPushButton("Browse")
        self.upload_folder_btn.clicked.connect(self.select_upload_folder)
        folder_layout = QtWidgets.QHBoxLayout()
        folder_layout.addWidget(self.upload_folder_input)
        folder_layout.addWidget(self.upload_folder_btn)
        self.settings_layout.addRow("Upload Folder:", folder_layout)

        buttons_layout = QtWidgets.QHBoxLayout()
        self.save_local_btn = QtWidgets.QPushButton("Save Local Settings")
        self.save_local_btn.clicked.connect(self.save_local_settings_only)
        self.save_all_btn = QtWidgets.QPushButton("Save All Settings")
        self.save_all_btn.clicked.connect(self.save_all_settings)
        buttons_layout.addWidget(self.save_local_btn)
        buttons_layout.addWidget(self.save_all_btn)
        self.settings_layout.addRow(buttons_layout)

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
        if self.settings_thread_running:
            return
        self.settings_thread_running = True
        self.loading_spinner.show()
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
            print(f"Updated API_BASE to {API_BASE} from settings")

        worker = NetworkWorker("GET", f"{API_BASE}/settings", HEADERS)
        worker.result.connect(self.handle_load_settings)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_load_settings(self, data, error_msg, success):
        self.settings_thread_running = False
        self.loading_spinner.hide()
        global UPLOAD_FOLDER
        if success:
            UPLOAD_FOLDER = data.get("upload_folder", "")
            self.upload_folder_input.setText(UPLOAD_FOLDER)
        else:
            print("Error loading upload folder from backend:", error_msg)

    def save_local_settings_only(self):
        global API_BASE
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
        QtWidgets.QMessageBox.information(self, "Success", "Server settings saved to local file.")
        self.on_tab_changed(self.current_tab)  # Refresh current tab

    def save_all_settings(self):
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

        UPLOAD_FOLDER = self.upload_folder_input.text().strip()
        data = {"upload_folder": UPLOAD_FOLDER}
        self.loading_spinner.show()
        worker = NetworkWorker("POST", f"{API_BASE}/settings", HEADERS, json_data=data)
        worker.result.connect(self.handle_save_all_settings)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_save_all_settings(self, data, error_msg, success):
        self.loading_spinner.hide()
        if success:
            QtWidgets.QMessageBox.information(self, "Success", "All settings saved successfully.")
        else:
            QtWidgets.QMessageBox.critical(self, "Error", f"Failed to save settings to backend:\n{error_msg}")
        self.on_tab_changed(self.current_tab)  # Refresh current tab

    # --- Refresh Tables & Logs ---
    def refresh_tables(self):
        if self.devices_thread_running or self.pending_thread_running:
            return  # Prevent overlapping threads

        self.refresh_devices_btn.setEnabled(False)
        self.refresh_pending_btn.setEnabled(False)
        self.loading_spinner.show()
        self.devices_thread_running = True
        worker = NetworkWorker("GET", f"{API_BASE}/devices", HEADERS)
        worker.result.connect(self.handle_refresh_devices)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_refresh_devices(self, data, error_msg, success):
        self.devices_thread_running = False
        devices = data if success else []
        if not success:
            print("Error fetching devices:", error_msg)

        self.devices_table.setRowCount(len(devices))
        for row, device in enumerate(devices):
            self.devices_table.setItem(row, 0, QtWidgets.QTableWidgetItem(device["ip"]))
            self.devices_table.setItem(row, 1, QtWidgets.QTableWidgetItem(device["name"]))
            self.devices_table.setItem(row, 2, QtWidgets.QTableWidgetItem(device.get("last_request_time", "Never")))

            delete_btn = QtWidgets.QPushButton("Delete")
            delete_btn.clicked.connect(lambda _, ip=device["ip"]: self.delete_device(ip))

            container = QtWidgets.QWidget()
            layout = QtWidgets.QHBoxLayout(container)
            layout.addWidget(delete_btn)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.setAlignment(QtCore.Qt.AlignCenter)

            self.devices_table.setCellWidget(row, 3, container)
            self.devices_table.setColumnWidth(3, int(self.devices_table.width() * 0.1))

        if not self.pending_thread_running:
            self.pending_thread_running = True
            worker = NetworkWorker("GET", f"{API_BASE}/pending", HEADERS)
            worker.result.connect(self.handle_refresh_pending)
            worker.finished.connect(lambda: self.cleanup_thread(worker))
            self.active_threads.append(worker)
            worker.start()
        else:
            self.loading_spinner.hide()
            self.refresh_devices_btn.setEnabled(True)
            self.refresh_pending_btn.setEnabled(True)

    def handle_refresh_pending(self, data, error_msg, success):
        self.pending_thread_running = False
        self.loading_spinner.hide()
        pending = data if success else []
        if not success:
            print("Error fetching pending devices:", error_msg)

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

        self.refresh_devices_btn.setEnabled(True)
        self.refresh_pending_btn.setEnabled(True)

    def refresh_logs(self):
        if self.logs_thread_running:
            return
        self.logs_thread_running = True
        self.refresh_logs_btn.setEnabled(False)
        self.loading_spinner.show()
        worker = NetworkWorker("GET", f"{API_BASE}/logs", HEADERS)
        worker.result.connect(self.handle_refresh_logs)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_refresh_logs(self, data, error_msg, success):
        self.logs_thread_running = False
        self.loading_spinner.hide()
        logs = data if success else []
        if not success:
            print("Error fetching logs:", error_msg)

        self.logs_table.setRowCount(len(logs))
        for row, log in enumerate(logs):
            self.logs_table.setItem(row, 0, QtWidgets.QTableWidgetItem(log["timestamp"]))
            self.logs_table.setItem(row, 1, QtWidgets.QTableWidgetItem(log["ip"]))
            self.logs_table.setItem(row, 2, QtWidgets.QTableWidgetItem(log["method"]))
            self.logs_table.setItem(row, 3, QtWidgets.QTableWidgetItem(log["url"]))
            self.logs_table.setItem(row, 4, QtWidgets.QTableWidgetItem(log["status"]))
        self.refresh_logs_btn.setEnabled(True)

    def clear_logs(self):
        self.loading_spinner.show()
        worker = NetworkWorker("POST", f"{API_BASE}/logs/clear", HEADERS)
        worker.result.connect(self.handle_clear_logs)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_clear_logs(self, data, error_msg, success):
        self.loading_spinner.hide()
        if not success:
            print("Error clearing logs:", error_msg)
        self.refresh_logs()

    # --- Device Actions ---
    def edit_name_dialog(self, row, column):
        if column != 1:
            return
        ip = self.devices_table.item(row, 0).text()
        old_name = self.devices_table.item(row, 1).text()
        new_name, ok = QtWidgets.QInputDialog.getText(self, "Edit Device Name", f"Change name for {ip}:", text=old_name)
        if ok and new_name.strip():
            self.loading_spinner.show()
            worker = NetworkWorker("POST", f"{API_BASE}/update_name", HEADERS, json_data={"ip": ip, "name": new_name.strip()})
            worker.result.connect(self.handle_edit_name)
            worker.finished.connect(lambda: self.cleanup_thread(worker))
            self.active_threads.append(worker)
            worker.start()

    def handle_edit_name(self, data, error_msg, success):
        self.loading_spinner.hide()
        if not success:
            print("Error updating name:", error_msg)
        self.refresh_tables()

    def delete_device(self, ip):
        confirm = QtWidgets.QMessageBox.question(self, "Delete Device", f"Are you sure you want to delete {ip}?",
                                                QtWidgets.QMessageBox.Yes | QtWidgets.QMessageBox.No)
        if confirm == QtWidgets.QMessageBox.Yes:
            self.loading_spinner.show()
            worker = NetworkWorker("POST", f"{API_BASE}/deny_device", HEADERS, json_data={"ip": ip})
            worker.result.connect(self.handle_delete_device)
            worker.finished.connect(lambda: self.cleanup_thread(worker))
            self.active_threads.append(worker)
            worker.start()

    def handle_delete_device(self, data, error_msg, success):
        self.loading_spinner.hide()
        if not success:
            print("Error deleting device:", error_msg)
        self.refresh_tables()

    def allow_device(self, ip):
        self.loading_spinner.show()
        worker = NetworkWorker("POST", f"{API_BASE}/allow_device", HEADERS, json_data={"ip": ip})
        worker.result.connect(self.handle_allow_device)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_allow_device(self, data, error_msg, success):
        self.loading_spinner.hide()
        if not success:
            print("Error allowing device:", error_msg)
        self.refresh_tables()

    def deny_device(self, ip):
        self.loading_spinner.show()
        worker = NetworkWorker("POST", f"{API_BASE}/deny_device", HEADERS, json_data={"ip": ip})
        worker.result.connect(self.handle_deny_device)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_deny_device(self, data, error_msg, success):
        self.loading_spinner.hide()
        if not success:
            print("Error denying device:", error_msg)
        self.refresh_tables()

    def check_server_status(self):
        worker = NetworkWorker("GET", f"{API_BASE}/settings", HEADERS)
        worker.result.connect(self.handle_server_status)
        worker.finished.connect(lambda: self.cleanup_thread(worker))
        self.active_threads.append(worker)
        worker.start()

    def handle_server_status(self, data, error_msg, success):
        self.status_label.setStyleSheet(f"color: {'green' if success else 'red'}; font-size: 20px;")
        self.status_text.setText("Server Online" if success else "Server Offline")

    def cleanup_thread(self, thread):
        if thread in self.active_threads:
            self.active_threads.remove(thread)
            thread.deleteLater()

# --- Run Application ---
if __name__ == "__main__":
    app = QtWidgets.QApplication(sys.argv)
    window = DeviceManager()
    window.show()
    sys.exit(app.exec_())