/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ORCOutputFormatFile.h"

#if USE_ORC
#    include <Formats/FormatFactory.h>
#    include <Processors/Formats/Impl/ORCBlockOutputFormat.h>

namespace local_engine
{
ORCOutputFormatFile::ORCOutputFormatFile(
    DB::ContextPtr context_,
    const std::string & file_uri_,
    WriteBufferBuilderPtr write_buffer_builder_,
    const DB::Block & preferred_schema_)
    : OutputFormatFile(context_, file_uri_, write_buffer_builder_, preferred_schema_)
{
}

OutputFormatFile::OutputFormatPtr ORCOutputFormatFile::createOutputFormat(const DB::Block & header)
{
    auto res = std::make_shared<OutputFormatFile::OutputFormat>();
    res->write_buffer = write_buffer_builder->build(file_uri);

    auto new_header = createHeaderWithPreferredSchema(header);
    // TODO: align all spark orc config with ch orc config
    auto format_settings = DB::getFormatSettings(context);
    auto output_format = std::make_shared<DB::ORCBlockOutputFormat>(*(res->write_buffer), new_header, format_settings);
    res->output = output_format;
    return res;
}
}
#endif
