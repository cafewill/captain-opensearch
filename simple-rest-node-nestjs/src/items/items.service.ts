import { Injectable } from '@nestjs/common';
import { itemsDb, Item } from '../db';
import { CreateItemDto } from './dto/item.dto';

@Injectable()
export class ItemsService {
  findAll():                    Item[]        { return itemsDb.findAll(); }
  findById(id: number):         Item          { return itemsDb.findById(id); }
  create(dto: CreateItemDto):   Item          { return itemsDb.insert(dto as Item); }
  update(id: number, dto: CreateItemDto): Item { return itemsDb.update(id, dto as Item); }
  remove(id: number):           boolean       { return itemsDb.remove(id); }
}
