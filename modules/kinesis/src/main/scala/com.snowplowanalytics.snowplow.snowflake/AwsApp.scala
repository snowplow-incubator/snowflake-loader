/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.snowflake

import com.snowplowanalytics.snowplow.sources.kinesis.{KinesisSource, KinesisSourceConfig}
import com.snowplowanalytics.snowplow.sinks.kinesis.{KinesisSink, KinesisSinkConfig}

object GcpApp extends LoaderApp[KinesisSourceConfig, KinesisSinkConfig](BuildInfo) {

  override def source: SourceProvider = KinesisSource.build(_)

  override def badSink: SinkProvider = KinesisSink.resource(_)
}
