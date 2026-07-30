package tree

import (
	"archive/zip"
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// FileMergeResult describes what happened when merging a single JSON file.
type FileMergeResult struct {
	Action    string   // "created", "merged", "skipped", "source_missing", "error"
	AddedKeys []string // top-level keys added to the destination
	Error     error    // non-nil only when Action == "error"
}

// PluginInitResult describes the result for a single plugin.
type PluginInitResult struct {
	PluginID     string
	Source       string          // zip name or directory name
	ConfigResult FileMergeResult // plugin.json outcome
	SchemaResult FileMergeResult // schema.json outcome
	Error        error           // fatal error (unzip fail, missing plugin.id)
}

// PluginInitSummary aggregates results from InitializePluginConfigs.
type PluginInitSummary struct {
	Results []PluginInitResult
}

// deepCopyValue recursively deep-copies a JSON-compatible value.
func deepCopyValue(v any) any {
	switch vv := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(vv))
		for k, val := range vv {
			out[k] = deepCopyValue(val)
		}
		return out
	case []any:
		out := make([]any, len(vv))
		for i, val := range vv {
			out[i] = deepCopyValue(val)
		}
		return out
	default:
		return v
	}
}

// mergeValue merges src into dst. Destination values take precedence.
func mergeValue(dst, src any) any {
	dstMap, dstOk := dst.(map[string]any)
	srcMap, srcOk := src.(map[string]any)
	if dstOk && srcOk {
		return mergeObjects(dstMap, srcMap)
	}
	dstArr, dstOk := dst.([]any)
	srcArr, srcOk := src.([]any)
	if dstOk && srcOk {
		return mergeArrays(dstArr, srcArr)
	}
	return dst
}

// mergeObjects merges src into dst. Keys in dst are kept; new keys from src are deep-copied.
func mergeObjects(dst, src map[string]any) map[string]any {
	out := make(map[string]any, len(dst)+len(src))
	for k, v := range dst {
		out[k] = v
	}
	for k, srcV := range src {
		if dstV, ok := dst[k]; ok {
			out[k] = mergeValue(dstV, srcV)
		} else {
			out[k] = deepCopyValue(srcV)
		}
	}
	return out
}

// mergeArrays merges src into dst, deduplicating by string representation.
func mergeArrays(dst, src []any) []any {
	seen := make(map[string]bool, len(dst))
	out := make([]any, len(dst))
	for i, v := range dst {
		key := fmt.Sprintf("%v", v)
		seen[key] = true
		out[i] = v
	}
	for _, v := range src {
		key := fmt.Sprintf("%v", v)
		if !seen[key] {
			seen[key] = true
			out = append(out, v)
		}
	}
	return out
}

// deepMergeJSON merges src into dst at the top level.
// Returns the merged map and a list of top-level keys newly added from src.
func deepMergeJSON(dst, src map[string]any) (map[string]any, []string) {
	merged := make(map[string]any, len(dst)+len(src))
	var addedKeys []string

	for k, v := range dst {
		merged[k] = v
	}
	for k, srcV := range src {
		if dstV, ok := dst[k]; ok {
			merged[k] = mergeValue(dstV, srcV)
		} else {
			merged[k] = deepCopyValue(srcV)
			addedKeys = append(addedKeys, k)
		}
	}
	return merged, addedKeys
}

// mergeJSONFile merges a source JSON file into a destination JSON file.
// Replaces the old copyFileIfNotExists with deep merge semantics.
func mergeJSONFile(src, dst string) FileMergeResult {
	srcData, err := os.ReadFile(src)
	if err != nil {
		if os.IsNotExist(err) {
			return FileMergeResult{Action: "source_missing"}
		}
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to read source: %w", err)}
	}

	var srcMap map[string]any
	if err := json.Unmarshal(srcData, &srcMap); err != nil {
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to parse source JSON: %w", err)}
	}

	dstData, err := os.ReadFile(dst)
	if err != nil {
		if os.IsNotExist(err) {
			// Destination doesn't exist — write source verbatim.
			if err := os.WriteFile(dst, srcData, 0644); err != nil {
				return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to write destination: %w", err)}
			}
			keys := make([]string, 0, len(srcMap))
			for k := range srcMap {
				keys = append(keys, k)
			}
			return FileMergeResult{Action: "created", AddedKeys: keys}
		}
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to read destination: %w", err)}
	}

	var dstMap map[string]any
	if err := json.Unmarshal(dstData, &dstMap); err != nil {
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to parse destination JSON: %w", err)}
	}

	merged, addedKeys := deepMergeJSON(dstMap, srcMap)
	if len(addedKeys) == 0 {
		dstJSON, _ := json.Marshal(dstMap)
		mergedJSON, _ := json.Marshal(merged)
		if string(dstJSON) == string(mergedJSON) {
			return FileMergeResult{Action: "skipped"}
		}
		addedKeys = []string{"<nested changes>"}
	}

	mergedData, err := json.MarshalIndent(merged, "", "  ")
	if err != nil {
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to marshal merged JSON: %w", err)}
	}
	if err := os.WriteFile(dst, mergedData, 0644); err != nil {
		return FileMergeResult{Action: "error", Error: fmt.Errorf("failed to write merged destination: %w", err)}
	}
	return FileMergeResult{Action: "merged", AddedKeys: addedKeys}
}

// InitializePluginConfigs performs plugin initialization on CLI startup:
// unzips plugin archives if needed and merges plugin.json/schema.json into config dirs.
// Also processes plugin directories that are already extracted (e.g., installed via npm postinstall).
func InitializePluginConfigs(pluginDir, pluginConfigDir, pluginSchemaDir string) (*PluginInitSummary, error) {
	summary := &PluginInitSummary{}

	if err := os.MkdirAll(pluginConfigDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create plugin config dir: %w", err)
	}
	if err := os.MkdirAll(pluginSchemaDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create plugin schema dir: %w", err)
	}
	if err := os.MkdirAll(pluginDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create plugin dir: %w", err)
	}

	entries, err := os.ReadDir(pluginDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read plugin dir: %w", err)
	}

	processed := make(map[string]bool)

	for _, entry := range entries {
		if isDirEntry(pluginDir, entry) {
			continue
		}
		name := entry.Name()
		if !strings.HasSuffix(name, ".zip") {
			continue
		}

		zipPath := filepath.Join(pluginDir, name)
		baseName := strings.TrimSuffix(name, ".zip")
		extractDir := filepath.Join(pluginDir, baseName)

		result := PluginInitResult{Source: name}

		if _, err := os.Stat(extractDir); os.IsNotExist(err) {
			if err := unzip(zipPath, extractDir); err != nil {
				result.Error = fmt.Errorf("unzip failed: %w", err)
				summary.Results = append(summary.Results, result)
				processed[baseName] = true // skip pass 2 even for partial extractions
				continue
			}
		}

		processed[baseName] = true
		processExtractedPlugin(extractDir, pluginConfigDir, pluginSchemaDir, &result)
		summary.Results = append(summary.Results, result)
	}

	for _, entry := range entries {
		if !isDirEntry(pluginDir, entry) {
			continue
		}
		name := entry.Name()
		if processed[name] {
			continue
		}
		extractDir := filepath.Join(pluginDir, name)
		propsPath := filepath.Join(extractDir, "plugin.properties")
		if _, err := os.Stat(propsPath); os.IsNotExist(err) {
			continue
		}

		result := PluginInitResult{Source: name}
		processExtractedPlugin(extractDir, pluginConfigDir, pluginSchemaDir, &result)
		summary.Results = append(summary.Results, result)
	}

	return summary, nil
}

// isDirEntry reports whether entry refers to a directory, following symlinks.
// os.DirEntry.IsDir() uses Lstat semantics so a symlink to a directory returns false.
func isDirEntry(parent string, entry os.DirEntry) bool {
	if entry.IsDir() {
		return true
	}
	info, err := os.Stat(filepath.Join(parent, entry.Name()))
	if err != nil {
		return false
	}
	return info.IsDir()
}

// processExtractedPlugin reads plugin.id from plugin.properties under extractDir,
// then merges classes/plugin.json and classes/schema.json into the config dirs.
// It populates the PluginID, ConfigResult, SchemaResult and Error fields of result.
func processExtractedPlugin(extractDir, pluginConfigDir, pluginSchemaDir string, result *PluginInitResult) {
	propsPath := filepath.Join(extractDir, "plugin.properties")
	pluginID, err := readPluginID(propsPath)
	if err != nil {
		result.Error = fmt.Errorf("read plugin.id failed: %w", err)
		return
	}
	result.PluginID = pluginID

	srcPluginJson := filepath.Join(extractDir, "classes", "plugin.json")
	dstPluginJson := filepath.Join(pluginConfigDir, pluginID+".json")
	result.ConfigResult = mergeJSONFile(srcPluginJson, dstPluginJson)

	srcSchemaJson := filepath.Join(extractDir, "classes", "schema.json")
	dstSchemaJson := filepath.Join(pluginSchemaDir, pluginID+".json")
	result.SchemaResult = mergeJSONFile(srcSchemaJson, dstSchemaJson)
}

// unzip extracts a zip archive to the destination directory with zip-slip protection.
func unzip(src, dest string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()

	if err := os.MkdirAll(dest, 0755); err != nil {
		return err
	}

	for _, f := range r.File {
		fpath := filepath.Join(dest, f.Name)

		// Zip-slip protection
		if !strings.HasPrefix(fpath, filepath.Clean(dest)+string(os.PathSeparator)) {
			return fmt.Errorf("illegal file path in zip: %s", f.Name)
		}

		if f.FileInfo().IsDir() {
			os.MkdirAll(fpath, f.Mode())
			continue
		}

		if err := os.MkdirAll(filepath.Dir(fpath), 0755); err != nil {
			return err
		}

		outFile, err := os.OpenFile(fpath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			return err
		}
		defer outFile.Close()

		rc, err := f.Open()
		if err != nil {
			return err
		}
		defer rc.Close()

		if _, err := io.Copy(outFile, rc); err != nil {
			return err
		}
	}
	return nil
}

// readPluginID reads the plugin.id value from a plugin.properties file.
func readPluginID(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "plugin.id=") {
			return strings.TrimPrefix(line, "plugin.id="), nil
		}
	}
	if err := scanner.Err(); err != nil {
		return "", err
	}
	return "", fmt.Errorf("plugin.id not found in %s", path)
}
