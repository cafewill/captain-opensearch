'use strict';
const Database = require('better-sqlite3');

const db = new Database(':memory:');

db.exec(`
  CREATE TABLE IF NOT EXISTS items (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    description TEXT,
    price       INTEGER NOT NULL DEFAULT 0
  )
`);

module.exports = {
  findAll:  ()         => db.prepare('SELECT * FROM items ORDER BY id').all(),
  findById: (id)       => db.prepare('SELECT * FROM items WHERE id = ?').get(id),
  insert:   (item)     => {
    const stmt = db.prepare('INSERT INTO items (name, description, price) VALUES (?, ?, ?)');
    const info = stmt.run(item.name, item.description ?? null, item.price);
    return { id: info.lastInsertRowid, ...item };
  },
  update:   (id, item) => {
    db.prepare('UPDATE items SET name=?, description=?, price=? WHERE id=?')
      .run(item.name, item.description ?? null, item.price, id);
    return db.prepare('SELECT * FROM items WHERE id = ?').get(id);
  },
  remove:   (id)       => db.prepare('DELETE FROM items WHERE id = ?').run(id).changes > 0,
};
