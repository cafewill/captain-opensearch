import { Controller, Get, Post, Put, Delete, Body, Param, Req, Res, HttpStatus } from '@nestjs/common';
import { Request, Response } from 'express';
import { ItemsService } from './items.service';
import { CreateItemDto } from './dto/item.dto';
import { OpenSearchWebAppender } from '../opensearch.web-appender';

@Controller('api/items')
export class ItemsController {
  constructor(
    private readonly itemsService: ItemsService,
    private readonly appender:     OpenSearchWebAppender,
  ) {}

  @Get()
  findAll(@Req() req: Request, @Res() res: Response) {
    try {
      const items = this.itemsService.findAll();
      this.appender.log('INFO', `GET /api/items → ${items.length}건`, { trace_id: (req as any).traceId });
      return res.json({ status: true, message: '조회 성공', data: items });
    } catch (e: any) {
      return res.status(500).json({ status: false, message: e.message });
    }
  }

  @Get(':id')
  findById(@Param('id') id: string, @Req() req: Request, @Res() res: Response) {
    try {
      const item = this.itemsService.findById(Number(id));
      if (!item) return res.status(HttpStatus.NOT_FOUND).json({ status: false, message: 'Item not found' });
      return res.json({ status: true, message: '조회 성공', data: item });
    } catch (e: any) {
      return res.status(500).json({ status: false, message: e.message });
    }
  }

  @Post()
  create(@Body() dto: CreateItemDto, @Req() req: Request, @Res() res: Response) {
    try {
      if (!dto.name || dto.price == null) return res.status(400).json({ status: false, message: 'name, price 필수' });
      const item = this.itemsService.create(dto);
      this.appender.log('INFO', `POST /api/items → id=${item.id}`, { trace_id: (req as any).traceId });
      return res.status(HttpStatus.CREATED).json({ status: true, message: '등록 성공', data: item });
    } catch (e: any) {
      return res.status(500).json({ status: false, message: e.message });
    }
  }

  @Put(':id')
  update(@Param('id') id: string, @Body() dto: CreateItemDto, @Req() req: Request, @Res() res: Response) {
    try {
      if (!this.itemsService.findById(Number(id))) return res.status(HttpStatus.NOT_FOUND).json({ status: false, message: 'Item not found' });
      if (!dto.name || dto.price == null) return res.status(400).json({ status: false, message: 'name, price 필수' });
      const item = this.itemsService.update(Number(id), dto);
      return res.json({ status: true, message: '수정 성공', data: item });
    } catch (e: any) {
      return res.status(500).json({ status: false, message: e.message });
    }
  }

  @Delete(':id')
  remove(@Param('id') id: string, @Res() res: Response) {
    try {
      if (!this.itemsService.remove(Number(id))) return res.status(HttpStatus.NOT_FOUND).json({ status: false, message: 'Item not found' });
      return res.json({ status: true, message: '삭제 성공', data: null });
    } catch (e: any) {
      return res.status(500).json({ status: false, message: e.message });
    }
  }
}
