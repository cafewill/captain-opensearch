import Database from 'better-sqlite3';

const db = new Database(':memory:');

db.exec(`
  CREATE TABLE IF NOT EXISTS items (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    description TEXT,
    price       INTEGER NOT NULL DEFAULT 0
  )
`);

export interface Item {
  id?:          number;
  name:         string;
  description?: string;
  price:        number;
}

export const itemsDb = {
  findAll:  (): Item[]         => db.prepare('SELECT * FROM items ORDER BY id').all() as Item[],
  findById: (id: number): Item => db.prepare('SELECT * FROM items WHERE id = ?').get(id) as Item,
  insert:   (item: Item): Item => {
    const info = db.prepare('INSERT INTO items (name, description, price) VALUES (?, ?, ?)')
                   .run(item.name, item.description ?? null, item.price);
    return { id: info.lastInsertRowid as number, ...item };
  },
  update: (id: number, item: Item): Item => {
    db.prepare('UPDATE items SET name=?, description=?, price=? WHERE id=?')
      .run(item.name, item.description ?? null, item.price, id);
    return db.prepare('SELECT * FROM items WHERE id = ?').get(id) as Item;
  },
  remove: (id: number): boolean =>
    (db.prepare('DELETE FROM items WHERE id = ?').run(id).changes ?? 0) > 0,
};
