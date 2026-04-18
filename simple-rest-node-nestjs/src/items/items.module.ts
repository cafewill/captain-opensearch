import { Module } from '@nestjs/common';
import { ItemsController } from './items.controller';
import { ItemsService }    from './items.service';
import { OpenSearchWebAppender } from '../opensearch.web-appender';

@Module({
  controllers: [ItemsController],
  providers:   [ItemsService, OpenSearchWebAppender],
})
export class ItemsModule {}
