import os

from conan import ConanFile
from conan.tools import files
from conan.tools.cmake import CMake, CMakeDeps, CMakeToolchain, cmake_layout
from conan.tools.env import Environment, VirtualBuildEnv, VirtualRunEnv

class GlutenConan(ConanFile):
    description = """Gluten Cpp"""

    name = "gluten"
    settings = "os", "arch", "compiler", "build_type"

    options = {
        "shared": [True, False],
        "fPIC": [True, False],
        "with_parquet" : [True, False],
        "file_system" : ['hdfs', 'cfs', 'proton'],
        "enable_asan" : [True, False],
        "build_benchmarks": [True, False],
        "build_tests": [True, False],
        "build_examples": [True, False],
        "enable_exception_trace" : [True, False],
    }
    default_options = {
        "shared" : False,
        "fPIC" : True,
        "with_parquet": True,
        "file_system" : 'hdfs',
        "enable_asan" : False,
        "build_benchmarks": False,
        "build_tests": False,
        "build_examples": False,
        "enable_exception_trace" : True,
    }

    FB_VERSION = "2022.10.31.00"

    def requirements(self):
        if hasattr(self, "channel"):
            if self.channel is not None:
                self.requires(f"velox/{self.version}@{self.user}/{self.channel}", transitive_headers=True, transitive_libs=True)
        else:
            self.requires(f"velox/{self.version}", transitive_headers=True, transitive_libs=True)
        if self.options.build_benchmarks or self.options.build_tests:
            self.requires("benchmark/[>=1.6.0]")
        self.requires("glog/0.7.1")


    def build_requirements(self):
        self.test_requires("gtest/1.10.0")
        self.test_requires("jemalloc/5.3.0")
        self.test_requires("duckdb/0.8.1")

    def layout(self):
        # generates CMakeUserPresets.json
        cmake_layout(self, build_folder='build')

    def config_options(self):
        if str(self.settings.arch) in ['armv8', 'armv9']:
            self.options.enable_exception_trace = False
        if self.options.get_safe("ldb_build"):
            self.options.enable_exception_trace = False

    def configure(self):
        postfix="/*"
        bolt = f"velox{postfix}"  # velox/*
        self.options[bolt].spark_compatible = True
        self.options[bolt].enable_asan = self.options.enable_asan

        self.options[bolt].enable_exception_trace = self.options.get_safe("enable_exception_trace")
        self.options[bolt].file_system = self.options.file_system

        if self.options.build_examples \
            or self.options.build_tests \
            or self.options.build_benchmarks:
            self.options[bolt].enable_test = True
            self.options[bolt].enable_testutil = True

        openssl = f"openssl{postfix}"
        boost = f"boost{postfix}"
        folly = f"folly{postfix}"
        if self.options.get_safe("ldb_build"):
            self.options[bolt].enable_exception_trace = False
            self.options[folly].no_exception_tracer = True
            self.options[openssl].rand_seed = "devrandom"
            self.options[boost].filesystem_disable_statx = True
            self.options[boost].without_stacktrace = True


    def generate(self):
        build_env = VirtualBuildEnv(self)
        build_env.generate()

        run_env = VirtualRunEnv(self)
        run_env.generate()

        tc = CMakeToolchain(self, generator="Ninja")

        cxx_flags = ''
        if str(self.settings.arch) in ['x86', 'x86_64']:
            cxx_flags = f'{cxx_flags} -mno-avx512f '
            tc.cache_variables["CMAKE_CXX_FLAGS"] = cxx_flags
            tc.cache_variables["CMAKE_C_FLAGS"] = cxx_flags

        # To avoid R_AARCH64_CALL26 link error when binary file exceeds 127M
        # -mcmodel=large if -fPIC removed
        if str(self.settings.arch) in ['armv8', 'armv9']:
            cxx_flags = f'{cxx_flags}  -ffunction-sections -fdata-sections '

            # Support CRC & NEON on ARMv8
            if str(self.settings.arch) in ['armv8']:
                cxx_flags = f'{cxx_flags} -march=armv8.3-a'
            if str(self.settings.arch) in ['armv9']:
                cxx_flags = f'{cxx_flags} -march=-march=armv9-a'

            tc.cache_variables["CMAKE_CXX_FLAGS"] = cxx_flags
            tc.cache_variables["CMAKE_C_FLAGS"] = cxx_flags

        tc.cache_variables["VELOX_ENABLE_PARQUET"] = self.options.with_parquet
        if self.options.file_system == "hdfs":
            tc.cache_variables["ENABLE_HDFS"] = True
            tc.cache_variables["ENABLE_PROTON"] = False
            tc.cache_variables["ENABLE_CFS"] = False
        elif self.options.file_system == "cfs":
            tc.cache_variables["ENABLE_HDFS"] = False
            tc.cache_variables["ENABLE_PROTON"] = False
            tc.cache_variables["ENABLE_CFS"] = True
        elif self.options.file_system == "proton":
            tc.cache_variables["ENABLE_HDFS"] = True
            tc.cache_variables["ENABLE_PROTON"] = True
            tc.cache_variables["ENABLE_CFS"] = False
        tc.cache_variables["BUILD_BENCHMARKS"] = self.options.build_benchmarks
        tc.cache_variables["BUILD_TESTS"] = self.options.build_tests
        tc.cache_variables["BUILD_EXAMPLES"] = self.options.build_examples
        if self.options.build_benchmarks:
            tc.cache_variables["ENABLE_ORC"] = False
        if self.options.enable_asan:
            cxx_flags = f'{cxx_flags} -fsanitize=address -fno-omit-frame-pointer '
            tc.cache_variables["CMAKE_CXX_FLAGS"] = cxx_flags
            tc.cache_variables["CMAKE_C_FLAGS"] = cxx_flags

        tc.cache_variables["ENABLE_EXCEPTION_TRACE"] = self.options.get_safe("enable_exception_trace")
        if self.options.get_safe("enable_exception_trace"):
            tc.preprocessor_definitions["ENABLE_EXCEPTION_TRACE"] = 1
        else:
            if tc.preprocessor_definitions.get("ENABLE_EXCEPTION_TRACE", None) is not None:
                del tc.preprocessor_definitions["ENABLE_EXCEPTION_TRACE"]

        tc.generate()

        deps = CMakeDeps(self)
        deps.set_property("flex", "cmake_find_mode", "config") 
        deps.generate()

    def build(self):
        cmake = CMake(self)
        cmake.configure()
        cmake.build()

    def package(self):
        files.copy(self, "LICENSE", src=self.source_folder, dst=os.path.join(self.package_folder, "licenses"))
        files.copy(self, "CONTRIBUTING.md", src=self.source_folder, dst=os.path.join(self.package_folder, "licenses"))
        files.copy(self, "README.md", src=self.source_folder, dst=os.path.join(self.package_folder, "licenses"))

        cmake = CMake(self)
        cmake.configure()
        cmake.install()

    def package_info(self):
        self.cpp_info.set_property("cmake_file_name", "gluten")
        self.cpp_info.set_property("cmake_find_mode", "both")
        self.cpp_info.set_property("cmake_target_name", "gluten::gluten")

        self.cpp_info.includedirs = ['include']
        self.cpp_info.libs = ["velox_backend"]
        
        self.cpp_info.components["libgluten"].libs = ["gluten"]
        self.cpp_info.components["libgluten"].requires.append("velox::velox")

        self.cpp_info.components["velox_backend"].libs = ["velox_backend"]
        self.cpp_info.components["velox_backend"].requires.append("libgluten")
        self.cpp_info.components["velox_backend"].requires.append("velox::velox")

        self.cpp_info.requires.extend(["glog::glog", "velox::velox"])

