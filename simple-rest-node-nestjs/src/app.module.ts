import { Module, MiddlewareConsumer, NestModule } from '@nestjs/common';
import { ItemsModule }           from './items/items.module';
import { OpenSearchWebAppender } from './opensearch.web-appender';

@Module({
  imports:   [ItemsModule],
  providers: [OpenSearchWebAppender],
})
export class AppModule implements NestModule {
  constructor(private readonly appender: OpenSearchWebAppender) {}

  configure(consumer: MiddlewareConsumer) {
    consumer.apply(this.appender.use.bind(this.appender)).forRoutes('*');
  }
}
