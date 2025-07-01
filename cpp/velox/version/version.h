/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <iomanip>
#include <sstream>
#include <string>

namespace gluten {

struct BuildInfo {
  static constexpr const char* version = "b6e10049e";
  static constexpr const char* hash = "b6e10049e6b98e8c7b3468be524705c82708e493";
  static constexpr const char* shortHash = "b6e10049e";
  static constexpr const char* time = "2025-07-01 22:06:24";
  static constexpr const char* host = "spark-dev";
  static constexpr const char* systemName = "Ubuntu 20.04.5 LTS";
  static constexpr const char* processor = "32 core Intel(R) Xeon(R) Platinum 8260 CPU @ 2.40GHz";
  static constexpr const char* buildType = "Release";
  static constexpr const char* architecture = "x86_64";
  static constexpr bool isDirty = true;
  static constexpr const char* modifiedFiles = "cpp/gluten.conan.graph.html, cpp/velox/CMakeLists.txt, cpp/velox/compute/VeloxPlanConverter.cc, cpp/velox/compute/WholeStageResultIterator.cc, cpp/velox/compute/WholeStageResultIterator.h, cpp/velox/memory/VeloxMemoryManager.cc, cpp/velox/version/version.h";

  static std::string toString(bool full = false) {
    std::string versionInfo = "========================================\n";
    versionInfo += "          Gluten Build Information        \n";
    versionInfo += "========================================\n";

    versionInfo += "Gluten Version   : " + std::string(version) + "\n";
    versionInfo += "Git Commit ID    : " + std::string(hash) + "\n";
    versionInfo += "Build Type       : " + std::string(buildType) + "\n";
    versionInfo += "Build Time       : " + std::string(time) + "\n";

    if (isDirty) {
      versionInfo += "Dirty Build      : true\n";
      versionInfo += "Modified Files   : " + std::string(modifiedFiles) + "\n";
    } else {
      versionInfo += "Dirty Build      : false\n";
    }

    if (full) {
      versionInfo += "Host Name        : " + std::string(host) + "\n";
      versionInfo += "OS Name          : " + std::string(systemName) + "\n";
      versionInfo += "Architecture     : " + std::string(architecture) + "\n";
      versionInfo += "Build Processor  : " + std::string(processor) + "\n";
    }

    versionInfo += "========================================\n";
    return versionInfo;
  }
  static std::string toJson(bool full = false) {
    std::ostringstream oss;
    oss << std::boolalpha;
    oss << "{\n";
    oss << "  \"version\": \"" << version << "\",\n";
    oss << "  \"hash\": \"" << hash << "\",\n";
    oss << "  \"shortHash\": \"" << shortHash << "\",\n";
    oss << "  \"time\": \"" << time << "\",\n";
    oss << "  \"isDirty\": " << isDirty << ",\n";
    if (isDirty) {
      oss << "  \"modifiedFiles\": \"" << modifiedFiles << "\",\n";
    }
    if (full) {
      oss << "  \"host\": \"" << host << "\",\n";
      oss << "  \"systemName\": \"" << systemName << "\",\n";
      oss << "  \"architecture\": \"" << architecture << "\",\n";
      oss << "  \"processor\": \"" << processor << "\",\n";
    }
    oss << "  \"buildType\": \"" << buildType << "\"\n";
    oss << "}";
    return oss.str();
  }
};
std::string GlutenVersion(bool full = false) {
  return BuildInfo::toString(full);
}
} // namespace gluten
