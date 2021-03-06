// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#ifndef DORIS_BE_SRC_QUERY_EXPRS_JSON_FUNCTIONS_H
#define DORIS_BE_SRC_QUERY_EXPRS_JSON_FUNCTIONS_H

#include <rapidjson/document.h>
#include "runtime/string_value.h"

namespace doris {

enum JsonFunctionType {
    JSON_FUN_INT = 0, JSON_FUN_DOUBLE, JSON_FUN_STRING
};

class Expr;
class OpcodeRegistry;
class TupleRow;

struct JsonPath {
	std::string key; // key of a json object
	int idx;    // array index of a json array, -1 means not set
	bool is_valid;  // true if the path is successfully parsed

	JsonPath(const std::string& key_, int idx_, bool is_valid_):
		key(key_),
		idx(idx_),
		is_valid(is_valid_) {}

	std::string debug_string() {
		std::stringstream ss;
		ss << "key: " << key << ", idx: " << idx << ", valid: " << is_valid;
		return ss.str();
	}
};

class JsonFunctions {
public:
    static void init();
    static doris_udf::IntVal get_json_int(
        doris_udf::FunctionContext* context, const doris_udf::StringVal& json_str,
        const doris_udf::StringVal& path);
    static doris_udf::StringVal get_json_string(
        doris_udf::FunctionContext* context, const doris_udf::StringVal& json_str,
        const doris_udf::StringVal& path);
    static doris_udf::DoubleVal get_json_double(
        doris_udf::FunctionContext* context, const doris_udf::StringVal& json_str,
        const doris_udf::StringVal& path);

    static rapidjson::Value* get_json_object(
			FunctionContext* context,
            const std::string& json_string, const std::string& path_string,
            const JsonFunctionType& fntype, rapidjson::Document* document);

	static void json_path_prepare(
			doris_udf::FunctionContext*,
			doris_udf::FunctionContext::FunctionStateScope);

	static void json_path_close(
			doris_udf::FunctionContext*,
			doris_udf::FunctionContext::FunctionStateScope);
private:

	static void get_parsed_paths(
			const std::vector<std::string>& path_exprs,
			std::vector<JsonPath>* parsed_paths);
};
}
#endif
