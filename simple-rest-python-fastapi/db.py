"""
SQLite in-memory DB — H2 in-memory 상당
"""

import sqlite3
import threading

_lock = threading.Lock()
_conn = sqlite3.connect(':memory:', check_same_thread=False)
_conn.row_factory = sqlite3.Row
_conn.execute('''
    CREATE TABLE IF NOT EXISTS items (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        name        TEXT    NOT NULL,
        description TEXT,
        price       INTEGER NOT NULL DEFAULT 0
    )
''')
_conn.commit()


def _row_to_dict(row):
    if row is None:
        return None
    return {'id': row['id'], 'name': row['name'], 'description': row['description'], 'price': row['price']}


def find_all():
    with _lock:
        cur = _conn.execute('SELECT * FROM items ORDER BY id')
        return [_row_to_dict(r) for r in cur.fetchall()]


def find_by_id(item_id: int):
    with _lock:
        cur = _conn.execute('SELECT * FROM items WHERE id = ?', (item_id,))
        return _row_to_dict(cur.fetchone())


def insert(name: str, description: str, price: int):
    with _lock:
        cur = _conn.execute(
            'INSERT INTO items (name, description, price) VALUES (?, ?, ?)',
            (name, description, price),
        )
        _conn.commit()
        return find_by_id(cur.lastrowid)


def update(item_id: int, name: str, description: str, price: int):
    with _lock:
        _conn.execute(
            'UPDATE items SET name = ?, description = ?, price = ? WHERE id = ?',
            (name, description, price, item_id),
        )
        _conn.commit()
        return find_by_id(item_id)


def remove(item_id: int) -> bool:
    with _lock:
        cur = _conn.execute('DELETE FROM items WHERE id = ?', (item_id,))
        _conn.commit()
        return cur.rowcount > 0
