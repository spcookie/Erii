package path

import (
	"os"
	"path/filepath"
)

var (
	ConfDir         string
	ConfMetaDir     string
	EriiDir         string
	OptsPath        string
	EnvFile         string
	AppFile         string
	SoulsDir        string
	RulesDir        string
	McpDir          string
	PluginConfigDir string
	PluginDir       string
	PluginSchemaDir string
)

func init() {
	InitPaths("", "", "", "", "")
}

func InitPaths(confDir, confMetaDir, eriiDir, pluginDir, optsPath string) {
	if confDir != "" {
		ConfDir = confDir
	} else {
		ConfDir = resolveConfDir()
	}
	if confMetaDir != "" {
		ConfMetaDir = confMetaDir
	} else {
		ConfMetaDir = resolveConfMetaDir()
	}
	if eriiDir != "" {
		EriiDir = eriiDir
	} else {
		EriiDir = resolveEriiDir()
	}
	if optsPath != "" {
		OptsPath = optsPath
	} else {
		OptsPath = resolveOptsPath()
	}
	EnvFile = filepath.Join(ConfDir, ".env.local")
	AppFile = filepath.Join(ConfDir, "application.conf")
	SoulsDir = filepath.Join(ConfDir, "souls")
	RulesDir = filepath.Join(ConfDir, "rules")
	McpDir = filepath.Join(ConfDir, "mcp")
	PluginConfigDir = filepath.Join(ConfDir, "plugin")
	PluginSchemaDir = filepath.Join(ConfMetaDir, "schema")
	if pluginDir != "" {
		PluginDir = pluginDir
	} else {
		PluginDir = resolvePluginDir()
	}
}

func resolveConfMetaDir() string {
	return resolveDir(".conf", func() string {
		if cwd, err := os.Getwd(); err == nil {
			return filepath.Join(cwd, ".conf")
		}
		return "./.conf"
	})
}

func resolvePluginDir() string {
	return resolveDir("plugins", func() string {
		if cwd, err := os.Getwd(); err == nil {
			return filepath.Join(cwd, "plugins")
		}
		return "./plugins"
	})
}

func resolveConfDir() string {
	return resolveDir("conf", func() string {
		if cwd, err := os.Getwd(); err == nil {
			return filepath.Join(cwd, "conf")
		}
		return "./conf"
	})
}

func resolveEriiDir() string {
	return resolveDir(".erii", func() string {
		if cwd, err := os.Getwd(); err == nil {
			return filepath.Join(cwd, ".erii")
		}
		return "./erii"
	})
}

func resolveOptsPath() string {
	return resolveDir("opts", func() string {
		if cwd, err := os.Getwd(); err == nil {
			return filepath.Join(cwd, "opts")
		}
		return "./opts"
	})
}

// resolveDir searches for a directory named `name` starting from CWD,
// then from the executable directory, walking upward in both cases.
// If not found, it returns the result of fallback().
func resolveDir(name string, fallback func() string) string {
	// Try CWD first
	if cwd, err := os.Getwd(); err == nil {
		if dir := findDirUpward(cwd, name); dir != "" {
			return dir
		}
	}
	// Try executable directory
	if ex, err := os.Executable(); err == nil {
		if dir := findDirUpward(filepath.Dir(ex), name); dir != "" {
			return dir
		}
	}
	return fallback()
}

// findDirUpward walks from start upward, looking for a directory named `name`.
func findDirUpward(start, name string) string {
	candidate := filepath.Join(start, name)
	if stat, err := os.Stat(candidate); err == nil && stat.IsDir() {
		return candidate
	}
	for dir := filepath.Dir(start); ; dir = filepath.Dir(dir) {
		candidate = filepath.Join(dir, name)
		if stat, err := os.Stat(candidate); err == nil && stat.IsDir() {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
	}
	return ""
}
