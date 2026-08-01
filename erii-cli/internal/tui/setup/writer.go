package setup

import (
	"bufio"
	"fmt"
	"os"
	"regexp"
	"strconv"
	"strings"
)

const (
	defaultServerUsername = "eriix"
	defaultServerPassword = "@Aa123!"
)

// writeEnvLocal writes sensitive values (API keys, tokens, proxy config) to .env.local.
// Existing lines for the same keys are updated; new keys are appended.
func writeEnvLocal(d *SetupData, envFilePath string) error {
	envVars := buildEnvVars(d)

	var lines []string
	f, err := os.Open(envFilePath)
	if err == nil {
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			lines = append(lines, scanner.Text())
		}
		f.Close()
	}

	for key, value := range envVars {
		if value == "" {
			continue
		}
		found := false
		updated := make([]string, 0, len(lines)+1)
		for _, line := range lines {
			trimmed := strings.TrimSpace(line)
			// Skip comments and empty lines
			if trimmed == "" || strings.HasPrefix(trimmed, "#") {
				updated = append(updated, line)
				continue
			}
			// Extract key before '=' and compare exactly
			eqIdx := strings.Index(trimmed, "=")
			if eqIdx < 0 {
				updated = append(updated, line)
				continue
			}
			lineKey := strings.TrimSpace(trimmed[:eqIdx])
			if lineKey == key {
				if !found {
					updated = append(updated, key+"="+value)
					found = true
				}
				continue
			}
			updated = append(updated, line)
		}
		if !found {
			updated = append(updated, key+"="+value)
		}
		lines = updated
	}

	out, err := os.Create(envFilePath)
	if err != nil {
		return fmt.Errorf("cannot write .env.local: %w", err)
	}
	defer out.Close()

	for _, line := range lines {
		fmt.Fprintln(out, line)
	}
	return nil
}

// buildEnvVars maps sensitive SetupData fields to .env.local variable names.
func buildEnvVars(d *SetupData) map[string]string {
	vars := make(map[string]string)
	prov := d.Providers[d.SelectedProv]
	provKey := prov.Key
	coreKey := providerCoreKey(prov)
	if coreKey == "" {
		coreKey = provKey
	}

	envKey := strings.ReplaceAll(strings.ToUpper(provKey), "-", "_") + "_API_KEY"
	if d.APIKey != "" {
		vars[envKey] = d.APIKey
	}

	if d.EmbeddingEnabled && d.EmbeddingAPIKey != "" {
		vars["EMBEDDING_API_KEY"] = d.EmbeddingAPIKey
	}
	if d.SearchEnabled && d.SearchAPIKey != "" {
		vars["SEARCH_API_KEY"] = d.SearchAPIKey
	}
	if d.STTEnabled && d.STTAPIKey != "" {
		vars["STT_API_KEY"] = d.STTAPIKey
	}
	if d.VisionEnabled && d.VisionAPIKey != "" {
		vars["VISION_API_KEY"] = d.VisionAPIKey
	}
	// Browser URLs are not sensitive — they go directly to application.conf
	if d.ProxyEnabled {
		if d.HTTPProxy != "" {
			vars["HTTP_PROXY"] = d.HTTPProxy
		}
		if d.SOCKSProxy != "" {
			vars["SOCKS_PROXY"] = d.SOCKSProxy
		}
	}
	if d.BotToken != "" {
		vars["NAPCAT_TOKEN"] = d.BotToken
	}
	if d.ServerUsername != "" {
		vars["ERII_SERVER_USERNAME"] = d.ServerUsername
	}
	if d.ServerPassword != "" {
		vars["ERII_SERVER_PASSWORD"] = d.ServerPassword
	}

	return vars
}

func readCoreCredentials(envFilePath string) (string, string) {
	f, err := os.Open(envFilePath)
	if err != nil {
		return "", ""
	}
	defer f.Close()

	values := map[string]string{}
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		eqIdx := strings.IndexByte(line, '=')
		if eqIdx <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:eqIdx])
		if key != "ERII_SERVER_USERNAME" && key != "ERII_SERVER_PASSWORD" {
			continue
		}
		value := strings.TrimSpace(line[eqIdx+1:])
		if unquoted, err := strconv.Unquote(value); err == nil {
			value = unquoted
		} else if len(value) >= 2 && value[0] == '\'' && value[len(value)-1] == '\'' {
			value = value[1 : len(value)-1]
		}
		if value != "" {
			values[key] = value
		}
	}
	return values["ERII_SERVER_USERNAME"], values["ERII_SERVER_PASSWORD"]
}

// modifyConfig reads the existing application.conf, updates configured values in-place,
// and writes back. Unconfigured keys are left unchanged.
// Sensitive values (api-key, token, proxy) are NOT written here — they go to .env.local.
func modifyConfig(d *SetupData, filePath string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("cannot open config: %w", err)
	}

	var lines []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	f.Close()

	if err := scanner.Err(); err != nil {
		return fmt.Errorf("cannot read config: %w", err)
	}

	ctx := pathStack{}
	prov := d.Providers[d.SelectedProv]
	provKey := prov.Key
	coreKey := providerCoreKey(prov)
	if coreKey == "" {
		coreKey = provKey
	}
	choiceModel := hoconKeyToChoiceModel(coreKey)
	envKey := strings.ReplaceAll(strings.ToUpper(provKey), "-", "_") + "_API_KEY"

	for i := 0; i < len(lines); i++ {
		line := lines[i]
		trimmed := strings.TrimSpace(line)

		if isBlockStart(trimmed) && !strings.ContainsRune(trimmed, '}') {
			blockName := trimmed
			blockName = strings.TrimSuffix(blockName, " = {")
			blockName = strings.TrimSuffix(blockName, " ={")
			blockName = strings.TrimSuffix(blockName, " {")
			blockName = strings.TrimSpace(blockName)
			ctx.push(blockName)
		}

		if isBlockEnd(trimmed) && len(ctx) > 0 {
			ctx.pop()
		}

		// llm.choice-provider
		if ctx.match("llm") && isKey(trimmed, "choice-provider") {
			lines[i] = replaceHoconValue(line, choiceModel)
		}

		// Provider-specific llm blocks (new nested providers structure)
		if ctx.match("llm", "providers", coreKey) {
			if isKey(trimmed, "api-key") {
				lines[i] = migrateSensitiveToEnv(line, &d.APIKey, "${?"+envKey+"}")
			}
			if isKey(trimmed, "base-url") && d.BaseURL != "" {
				lines[i] = replaceHoconValue(line, d.BaseURL)
			}
		}

		// Provider models
		if ctx.match("llm", "providers", coreKey, "models") {
			if d.ModelMode == "all" && d.AllModel != "" {
				if isKey(trimmed, "lite") {
					lines[i] = replaceHoconValue(line, d.AllModel)
				}
				if isKey(trimmed, "flash") {
					lines[i] = replaceHoconValue(line, d.AllModel)
				}
				if isKey(trimmed, "pro") {
					lines[i] = replaceHoconValue(line, d.AllModel)
				}
			} else {
				if isKey(trimmed, "lite") && d.LiteModel != "" {
					lines[i] = replaceHoconValue(line, d.LiteModel)
				}
				if isKey(trimmed, "flash") && d.FlashModel != "" {
					lines[i] = replaceHoconValue(line, d.FlashModel)
				}
				if isKey(trimmed, "pro") && d.ProModel != "" {
					lines[i] = replaceHoconValue(line, d.ProModel)
				}
			}
		}

		// Provider settings
		if ctx.match("llm", "providers", coreKey, "settings") {
			for key, value := range d.LLMSettings {
				if isKey(trimmed, key) && value != "" {
					lines[i] = replaceHoconValue(line, value)
				}
			}
		}

		// LLM capabilities
		if ctx.exact("llm", "capability") {
			lines[i] = replaceCapabilityLine(line, trimmed, d.LLMCapability.Default)
		}
		if ctx.exact("llm", "capability", "lite") {
			lines[i] = replaceCapabilityLine(line, trimmed, d.LLMCapability.Lite)
		}
		if ctx.exact("llm", "capability", "flash") {
			lines[i] = replaceCapabilityLine(line, trimmed, d.LLMCapability.Flash)
		}
		if ctx.exact("llm", "capability", "pro") {
			lines[i] = replaceCapabilityLine(line, trimmed, d.LLMCapability.Pro)
		}

		// LLM usage pricing
		if ctx.exact("llm", "usage-pricing") {
			if isKey(trimmed, "price-unit") && d.LLMUsagePricing.PriceUnit != "" {
				lines[i] = replaceHoconValue(line, d.LLMUsagePricing.PriceUnit)
			}
		}
		if ctx.exact("llm", "usage-pricing", "lite") {
			lines[i] = replacePricingLine(line, trimmed, d.LLMUsagePricing.Lite)
		}
		if ctx.exact("llm", "usage-pricing", "flash") {
			lines[i] = replacePricingLine(line, trimmed, d.LLMUsagePricing.Flash)
		}
		if ctx.exact("llm", "usage-pricing", "pro") {
			lines[i] = replacePricingLine(line, trimmed, d.LLMUsagePricing.Pro)
		}

		// embedding
		if d.EmbeddingEnabled {
			if ctx.match("embedding") {
				if isKey(trimmed, "api-key") {
					lines[i] = migrateSensitiveToEnv(line, &d.EmbeddingAPIKey, "${?EMBEDDING_API_KEY}")
				}
				if isKey(trimmed, "url") && d.EmbeddingURL != "" {
					lines[i] = replaceHoconValue(line, d.EmbeddingURL)
				}
				if isKey(trimmed, "provider") && d.EmbeddingProvider != "" {
					lines[i] = replaceHoconValue(line, d.EmbeddingProvider)
				}
				if isKey(trimmed, "model") && d.EmbeddingModel != "" {
					lines[i] = replaceHoconValue(line, d.EmbeddingModel)
				}
			}
		}

		// search
		if d.SearchEnabled {
			if ctx.match("search") {
				if isKey(trimmed, "api-key") {
					lines[i] = migrateSensitiveToEnv(line, &d.SearchAPIKey, "${?SEARCH_API_KEY}")
				}
				if isKey(trimmed, "url") && d.SearchURL != "" {
					lines[i] = replaceHoconValue(line, d.SearchURL)
				}
				if isKey(trimmed, "provider") && d.SearchProvider != "" {
					lines[i] = replaceHoconValue(line, d.SearchProvider)
				}
			}
		}

		// stt
		if d.STTEnabled {
			if ctx.match("stt") {
				if isKey(trimmed, "api-key") {
					lines[i] = migrateSensitiveToEnv(line, &d.STTAPIKey, "${?STT_API_KEY}")
				}
				if isKey(trimmed, "url") && d.STTURL != "" {
					lines[i] = replaceHoconValue(line, d.STTURL)
				}
				if isKey(trimmed, "provider") && d.STTProvider != "" {
					lines[i] = replaceHoconValue(line, d.STTProvider)
				}
				if isKey(trimmed, "model") && d.STTModel != "" {
					lines[i] = replaceHoconValue(line, d.STTModel)
				}
			}
		}

		// vision
		if d.VisionEnabled {
			if ctx.match("vision") {
				if isKey(trimmed, "api-key") {
					lines[i] = migrateSensitiveToEnv(line, &d.VisionAPIKey, "${?VISION_API_KEY}")
				}
				if isKey(trimmed, "url") && d.VisionURL != "" {
					lines[i] = replaceHoconValue(line, d.VisionURL)
				}
				if isKey(trimmed, "provider") && d.VisionProvider != "" {
					lines[i] = replaceHoconValue(line, d.VisionProvider)
				}
			}
		}

		// browser (non-sensitive only)
		if d.BrowserEnabled {
			if ctx.match("browser") {
				if isKey(trimmed, "download") {
					lines[i] = replaceHoconValue(line, fmt.Sprintf("%v", d.BrowserDownload))
				}
				if isKey(trimmed, "playwright-url") && d.PlaywrightURL != "" {
					lines[i] = replaceHoconValue(line, d.PlaywrightURL)
				}
				if isKey(trimmed, "external-host") && d.ExternalHost != "" {
					lines[i] = replaceHoconValue(line, d.ExternalHost)
				}
			}
		}

		// proxy
		if d.ProxyEnabled {
			if ctx.match("proxy") {
				if isKey(trimmed, "http") {
					lines[i] = migrateSensitiveToEnv(line, &d.HTTPProxy, "${?HTTP_PROXY}")
				}
				if isKey(trimmed, "socks") {
					lines[i] = migrateSensitiveToEnv(line, &d.SOCKSProxy, "${?SOCKS_PROXY}")
				}
			}
		}

		// onebot.bots.erii
		if ctx.match("onebot", "bots", "erii") {
			if isKey(trimmed, "ws") && d.BotWS != "" {
				lines[i] = replaceHoconValue(line, d.BotWS)
			}
			if isKey(trimmed, "token") {
				lines[i] = migrateSensitiveToEnv(line, &d.BotToken, "${NAPCAT_TOKEN}")
			}
		}

		// groups (global)
		if ctx.match("groups") {
			if isKey(trimmed, "debug-group-id") {
				if d.DebugGroupID != "" {
					lines[i] = replaceHoconValue(line, d.DebugGroupID)
				} else {
					lines[i] = replaceHoconValue(line, "null")
				}
			}
			if isKey(trimmed, "enable-groups") {
				if d.EnableGroups != "" {
					lines[i] = replaceHoconArray(line, d.EnableGroups)
				}
			}
			if isKey(trimmed, "message-redirect-map") {
				if d.MessageRedirectMap != "" {
					lines[i] = replaceHoconArray(line, d.MessageRedirectMap)
				}
			}
		}
	}

	// Replace onebot.bots.erii.groups placeholder with actual groups from enable-groups
	if d.EnableGroups != "" {
		lines = replaceEririGroups(lines, d.EnableGroups, d.BotAdmins)
	}

	out, err := os.Create(filePath)
	if err != nil {
		return fmt.Errorf("cannot write config: %w", err)
	}
	defer out.Close()

	for _, line := range lines {
		fmt.Fprintln(out, line)
	}
	return nil
}

// replaceEririGroups replaces the placeholder group entries inside
// onebot.bots.erii.groups { ... } with groups derived from enableGroups.
func replaceEririGroups(lines []string, enableGroups string, admins string) []string {
	ctx := pathStack{}
	groupStart := -1
	groupEnd := -1

	for i, line := range lines {
		trimmed := strings.TrimSpace(line)

		if isBlockStart(trimmed) && !strings.ContainsRune(trimmed, '}') {
			blockName := extractBlockName(trimmed)
			ctx.push(blockName)
		}

		if isBlockEnd(trimmed) && len(ctx) > 0 {
			ctx.pop()
		}

		if groupStart < 0 && ctx.match("onebot", "bots", "erii") && isBlockStart(trimmed) && extractBlockName(trimmed) == "groups" {
			groupStart = i
		}
	}

	// Find closing brace
	if groupStart >= 0 {
		depth := 1
		for i := groupStart + 1; i < len(lines); i++ {
			trimmed := strings.TrimSpace(lines[i])
			depth += strings.Count(trimmed, "{") - strings.Count(trimmed, "}")
			if depth <= 0 {
				groupEnd = i
				break
			}
		}
	}

	if groupStart < 0 || groupEnd < 0 {
		return lines
	}

	indent := detectIndent(lines[groupStart])
	innerIndent := indent + "    "
	groupIDs := splitCSV(enableGroups)
	adminList := splitCSV(admins)
	adminStr := strings.Join(adminList, ", ")

	var result []string
	result = append(result, lines[:groupStart+1]...)
	for _, gid := range groupIDs {
		result = append(result, fmt.Sprintf("%s%s = {", innerIndent, gid))
		result = append(result, fmt.Sprintf("%s    admins = [%s]", innerIndent, adminStr))
		result = append(result, fmt.Sprintf("%s    desire = 15", innerIndent))
		result = append(result, fmt.Sprintf("%s}", innerIndent))
	}
	result = append(result, lines[groupEnd:]...)
	return result
}

func extractBlockName(line string) string {
	s := strings.TrimSpace(line)
	s = strings.TrimSuffix(s, " = {")
	s = strings.TrimSuffix(s, " ={")
	s = strings.TrimSuffix(s, " {")
	return strings.TrimSpace(s)
}

func detectIndent(line string) string {
	for i, r := range line {
		if r != ' ' && r != '\t' {
			return line[:i]
		}
	}
	return ""
}

func splitCSV(s string) []string {
	parts := splitByCommas(s)
	var result []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			result = append(result, p)
		}
	}
	return result
}

// ---- Lightweight HOCON path tracking ----

type pathStack []string

func (p *pathStack) push(name string) {
	*p = append(*p, name)
}

func (p *pathStack) pop() {
	if len(*p) > 0 {
		*p = (*p)[:len(*p)-1]
	}
}

func (p pathStack) match(path ...string) bool {
	if len(p) < len(path) {
		return false
	}
	for i, seg := range path {
		if p[i] != seg {
			return false
		}
	}
	return true
}

func (p pathStack) exact(path ...string) bool {
	if len(p) != len(path) {
		return false
	}
	return p.match(path...)
}

// ---- Helpers ----

var (
	blockStartRe = regexp.MustCompile(`^\s*[\w\-.*]+\s*(=\s*)?\{`)
	blockEndRe   = regexp.MustCompile(`^\s*}\s*$`)
	keyValueRe   = regexp.MustCompile(`^(\s*[\w\-.]+\s*=\s*)(.+)$`)
	arrayRe      = regexp.MustCompile(`^(\s*[\w\-.]+\s*=\s*)\[.*?\].*$`)
)

func isBlockStart(line string) bool {
	return blockStartRe.MatchString(line)
}

func isBlockEnd(line string) bool {
	return blockEndRe.MatchString(line)
}

func isKey(line string, key string) bool {
	trimmed := strings.TrimSpace(line)
	return strings.HasPrefix(trimmed, key+" ") || strings.HasPrefix(trimmed, key+"=")
}

func replaceHoconValue(line string, value string) string {
	matches := keyValueRe.FindStringSubmatch(line)
	if matches == nil {
		return line
	}
	// Preserve quoting if original value was quoted
	origValue := strings.TrimSpace(matches[2])
	if strings.HasPrefix(origValue, "\"") || strings.HasPrefix(origValue, "${") {
		return matches[1] + "\"" + value + "\""
	}
	return matches[1] + value
}

// migrateSensitiveToEnv checks if a HOCON line has a plaintext sensitive value.
// If so, captures it into *target (when empty) and replaces the line with ${?VAR}.
// Lines already using ${?} are left unchanged.
func migrateSensitiveToEnv(line string, target *string, envRef string) string {
	matches := keyValueRe.FindStringSubmatch(line)
	if matches == nil {
		return line
	}
	origValue := strings.TrimSpace(matches[2])
	// Already an env reference — switch to the selected provider preset's env var.
	if strings.HasPrefix(origValue, "${") {
		return matches[1] + envRef
	}
	// Plaintext — strip quotes and capture
	val := strings.Trim(origValue, "\"")
	if *target == "" && val != "" {
		*target = val
	}
	return matches[1] + envRef
}

func replaceCapabilityLine(line string, trimmed string, c LLMCapabilitySet) string {
	switch {
	case isKey(trimmed, "completion"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.Completion))
	case isKey(trimmed, "prompt-caching"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.PromptCaching))
	case isKey(trimmed, "temperature"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.Temperature))
	case isKey(trimmed, "tools"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.Tools))
	case isKey(trimmed, "tool-choice"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.ToolChoice))
	case isKey(trimmed, "multiple-choices"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.MultipleChoices))
	case isKey(trimmed, "thinking"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.Thinking))
	case isKey(trimmed, "vision-image"):
		return replaceHoconValue(line, fmt.Sprintf("%v", c.VisionImage))
	default:
		return line
	}
}

func replacePricingLine(line string, trimmed string, p LLMPricingTierDefaults) string {
	switch {
	case isKey(trimmed, "input-cache-hit"):
		return replaceHoconValue(line, fmt.Sprintf("%g", p.InputCacheHit))
	case isKey(trimmed, "input-cache-miss"):
		return replaceHoconValue(line, fmt.Sprintf("%g", p.InputCacheMiss))
	case isKey(trimmed, "output"):
		return replaceHoconValue(line, fmt.Sprintf("%g", p.Output))
	default:
		return line
	}
}

func replaceHoconArray(line string, csv string) string {
	matches := arrayRe.FindStringSubmatch(line)
	if matches == nil {
		return line
	}
	parts := splitByCommas(csv)
	elems := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			elems = append(elems, p)
		}
	}
	return fmt.Sprintf("%s[%s]", matches[1], strings.Join(elems, ", "))
}
