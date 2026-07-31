package api

import (
	"encoding/json"
	"fmt"
)

// ── Paginated Response ──

type PaginatedResponse[T any] struct {
	Items  []T `json:"items"`
	Total  int `json:"total"`
	Offset int `json:"offset"`
	Limit  int `json:"limit"`
}

// ── Bot / Group (shared) ──

type BotInfo struct {
	BotID   string `json:"botId"`
	BotName string `json:"botName"`
}

type GroupInfo struct {
	GroupID   string `json:"groupId"`
	GroupName string `json:"groupName"`
}

// ── PAD ──

type PAD struct {
	P float64 `json:"p"`
	A float64 `json:"a"`
	D float64 `json:"d"`
}

// ── BehaviorProfile ──

type BehaviorProfile struct {
	Emotion        string `json:"emotion"`
	Tone           string `json:"tone"`
	Aggressiveness string `json:"aggressiveness"`
	EmojiLevel     string `json:"emojiLevel"`
}

// ── Facts ──

type FactRecord struct {
	ID          int      `json:"id"`
	BotID     string   `json:"botId"`
	GroupID     string   `json:"groupId"`
	Keyword     string   `json:"keyword"`
	Description string   `json:"description"`
	Values      string   `json:"values"`
	Entities    []string `json:"entities"`
	Subjects    string   `json:"subjects"`
	ScopeType   string   `json:"scopeType"`
	CreatedAt   string   `json:"createdAt"`
	ValidFrom   string   `json:"validFrom"`
	ValidTo     *string  `json:"validTo"`
	VectorID    *string  `json:"vectorId"`
	Valid       bool     `json:"valid"`
}

type FactRequest struct {
	Keyword     string   `json:"keyword"`
	Description string   `json:"description"`
	Entities    []string `json:"entities"`
	Subjects    string   `json:"subjects"`
	ScopeType   string   `json:"scopeType"`
}

type MemorySearchRequest struct {
	Query string `json:"query"`
	Limit int    `json:"limit"`
}

type MemoryFactSearchResult struct {
	Fact     FactRecord `json:"fact"`
	Score    *float64   `json:"score"`
	VectorID *string    `json:"vectorId"`
	Source   string     `json:"source"`
}

type MemoryGraphNode struct {
	ID     string `json:"id"`
	Type   string `json:"type"`
	Label  string `json:"label"`
	Source string `json:"source"`
}

type MemoryGraphEdge struct {
	From  string `json:"from"`
	To    string `json:"to"`
	Label string `json:"label"`
}

type MemoryVectorSearchResponse struct {
	Query   string                   `json:"query"`
	Results []MemoryFactSearchResult `json:"results"`
}

type MemoryGraphSearchResponse struct {
	Query           string                   `json:"query"`
	SeedResults     []MemoryFactSearchResult `json:"seedResults"`
	ExpandedResults []MemoryFactSearchResult `json:"expandedResults"`
	Nodes           []MemoryGraphNode        `json:"nodes"`
	Edges           []MemoryGraphEdge        `json:"edges"`
}

// ── User Profiles ──

type UserProfileRecord struct {
	ID          int    `json:"id"`
	BotID     string `json:"botId"`
	GroupID     string `json:"groupId"`
	UserID      string `json:"userId"`
	Profile     string `json:"profile"`
	Preferences string `json:"preferences"`
	CreatedAt   string `json:"createdAt"`
}

type UpdateUserProfileRequest struct {
	Profile     string `json:"profile"`
	Preferences string `json:"preferences"`
}

// ── Memes ──

type MemeRecord struct {
	ID                int     `json:"id"`
	BotID             string  `json:"botId"`
	GroupID           string  `json:"groupId"`
	ResourceID        int     `json:"resourceId"`
	Md5               string  `json:"md5"`
	SeenCount         int     `json:"seenCount"`
	LastAnalyzedCount int     `json:"lastAnalyzedCount"`
	Description       *string `json:"description"`
	Purpose           *string `json:"purpose"`
	Tags              *string `json:"tags"`
	UsageCount        int     `json:"usageCount"`
	CreatedAt         string  `json:"createdAt"`
	UpdatedAt         string  `json:"updatedAt"`
}

type UpdateMemeRequest struct {
	Description *string `json:"description"`
	Purpose     *string `json:"purpose"`
	Tags        *string `json:"tags"`
}

// ── Meme Search ──

type MemeSearchRequest struct {
	Query string `json:"query"`
	Limit int    `json:"limit"`
}

type MemeVectorSearchResult struct {
	Meme     MemeRecord `json:"meme"`
	Score    *float64   `json:"score"`
	VectorID *string    `json:"vectorId"`
	Source   string     `json:"source"`
}

type MemeVectorSearchResponse struct {
	Query   string                   `json:"query"`
	Results []MemeVectorSearchResult `json:"results"`
}

// ── Vocabulary ──

type VocabRecord struct {
	ID        int    `json:"id"`
	BotID   string `json:"botId"`
	GroupID   string `json:"groupId"`
	Word      string `json:"word"`
	Type      string `json:"type"`
	Meaning   string `json:"meaning"`
	Example   string `json:"example"`
	Weight    int    `json:"weight"`
	LastSeen  string `json:"lastSeen"`
	CreatedAt string `json:"createdAt"`
}

type VocabRequest struct {
	Word    string `json:"word"`
	Type    string `json:"type"`
	Meaning string `json:"meaning"`
	Example string `json:"example"`
	Weight  int    `json:"weight"`
}

// ── Summaries ──

type SummaryRecord struct {
	ID               int     `json:"id"`
	BotID          string  `json:"botId"`
	GroupID          string  `json:"groupId"`
	TimeRange        string  `json:"timeRange"`
	Content          string  `json:"content"`
	KeyPoints        string  `json:"keyPoints"`
	EmotionalTone    *string `json:"emotionalTone"`
	ParticipantCount int     `json:"participantCount"`
	MessageCount     int     `json:"messageCount"`
	CreatedAt        string  `json:"createdAt"`
}

type UpdateSummaryRequest struct {
	TimeRange     string  `json:"timeRange"`
	Content       string  `json:"content"`
	KeyPoints     string  `json:"keyPoints"`
	EmotionalTone *string `json:"emotionalTone"`
}

// ── History ──

type HistoryRecord struct {
	ID          int            `json:"id"`
	BotID     string         `json:"botId"`
	GroupID     string         `json:"groupId"`
	UserID      string         `json:"userId"`
	Nick        string         `json:"nick"`
	MessageType string         `json:"messageType"`
	Content     *string        `json:"content"`
	Resource    *ResourceBrief `json:"resource"`
	CreatedAt   string         `json:"createdAt"`
}

type ResourceBrief struct {
	ID       int    `json:"id"`
	FileName string `json:"fileName"`
	URL      string `json:"url"`
}

type UpdateHistoryRequest struct {
	Content *string `json:"content"`
	Nick    string  `json:"nick"`
}

// ── Resources ──

type ResourceRecord struct {
	ID        int    `json:"id"`
	BotID   string `json:"botId"`
	GroupID   string `json:"groupId"`
	URL       string `json:"url"`
	FileName  string `json:"fileName"`
	Size      int64  `json:"size"`
	Md5       string `json:"md5"`
	CreatedAt string `json:"createdAt"`
}

// ── Emotion ──

type EmotionRecord struct {
	ID                      int             `json:"id"`
	BotID                 string          `json:"botId"`
	GroupID                 string          `json:"groupId"`
	EmotionalTendency       string          `json:"emotionalTendency"`
	Stimulus                PAD             `json:"stimulus"`
	Emotion                 PAD             `json:"emotion"`
	Mood                    PAD             `json:"mood"`
	Behavior                BehaviorProfile `json:"behavior"`
	HistoryMessageProcessed int             `json:"historyMessageProcessed"`
}

type UpdateEmotionRequest struct {
	EmotionalTendency string          `json:"emotionalTendency"`
	Stimulus          PAD             `json:"stimulus"`
	Emotion           PAD             `json:"emotion"`
	Mood              PAD             `json:"mood"`
	Behavior          BehaviorProfile `json:"behavior"`
}

// ── Flow ──

type FlowRecord struct {
	ID                     int     `json:"id"`
	BotID                string  `json:"botId"`
	GroupID                string  `json:"groupId"`
	LastProcessedHistoryId int     `json:"lastProcessedHistoryId"`
	LastProcessedAt        string  `json:"lastProcessedAt"`
	CurrentTopic           string  `json:"currentTopic"`
	FlowValue              float64 `json:"flowValue"`
	LastUpdateTime         int64   `json:"lastUpdateTime"`
}

type UpdateFlowRequest struct {
	FlowValue    float64 `json:"flowValue"`
	CurrentTopic string  `json:"currentTopic"`
}

// ── Volition ──

type VolitionRecord struct {
	ID                     int     `json:"id"`
	BotID                string  `json:"botId"`
	GroupID                string  `json:"groupId"`
	Fatigue                float64 `json:"fatigue"`
	Stimulus               float64 `json:"stimulus"`
	LastActiveTime         int64   `json:"lastActiveTime"`
	LastProcessedHistoryId int     `json:"lastProcessedHistoryId"`
	LastProcessedAt        string  `json:"lastProcessedAt"`
}

type UpdateVolitionRequest struct {
	Fatigue  float64 `json:"fatigue"`
	Stimulus float64 `json:"stimulus"`
}

// ── Token Usage ──

type TokenUsageChartPoint struct {
	Name           string `json:"name"`
	CacheHitInput  int64  `json:"cacheHitInput"`
	CacheMissInput int64  `json:"cacheMissInput"`
	Output         int64  `json:"output"`
}

type DailyTokenUsagePoint struct {
	Date   string  `json:"date"`
	Tokens int64   `json:"tokens"`
	Cost   float64 `json:"cost"`
}

type DailyTokenUsageSummary struct {
	Date           string                 `json:"date"`
	CacheHitInput  int64                  `json:"cacheHitInput"`
	CacheMissInput int64                  `json:"cacheMissInput"`
	Output         int64                  `json:"output"`
	Cost           float64                `json:"cost"`
	CacheHitRate   float64                `json:"cacheHitRate"`
	SceneBars      []TokenUsageChartPoint `json:"sceneBars"`
	ModelBars      []TokenUsageChartPoint `json:"modelBars"`
}

type TokenUsageTierPricing struct {
	InputCacheHit  float64 `json:"inputCacheHit"`
	InputCacheMiss float64 `json:"inputCacheMiss"`
	Output         float64 `json:"output"`
}

type TokenUsagePricing struct {
	Lite  TokenUsageTierPricing `json:"lite"`
	Flash TokenUsageTierPricing `json:"flash"`
	Pro   TokenUsageTierPricing `json:"pro"`
}

type TokenUsageSummary struct {
	TodayCacheHitInput  int64                    `json:"todayCacheHitInput"`
	TodayCacheMissInput int64                    `json:"todayCacheMissInput"`
	TodayOutput         int64                    `json:"todayOutput"`
	TodayCost           float64                  `json:"todayCost"`
	PriceUnit           string                   `json:"priceUnit"`
	Pricing             *TokenUsagePricing       `json:"pricing"`
	TotalCacheHitInput  int64                    `json:"totalCacheHitInput"`
	TotalCacheMissInput int64                    `json:"totalCacheMissInput"`
	TotalOutput         int64                    `json:"totalOutput"`
	TotalCost           float64                  `json:"totalCost"`
	TodayCacheHitRate   float64                  `json:"todayCacheHitRate"`
	SceneBars           []TokenUsageChartPoint   `json:"sceneBars"`
	ModelBars           []TokenUsageChartPoint   `json:"modelBars"`
	DailySeries         []DailyTokenUsagePoint   `json:"dailySeries"`
	DailyViews          []DailyTokenUsageSummary `json:"dailyViews"`
}

// ── Status specific ──

type FlowState struct {
	Meter float64 `json:"meter"`
	State string  `json:"state"`
}

type VolitionState struct {
	Stimulus    float64 `json:"stimulus"`
	Fatigue     float64 `json:"fatigue"`
	ShouldSpeak bool    `json:"shouldSpeak"`
}

type Fact struct {
	Keyword     string   `json:"keyword"`
	Description string   `json:"description"`
	Values      string   `json:"values"`
	Subjects    []string `json:"subjects"`
}

type Facts struct {
	Group []Fact `json:"group"`
	User  []Fact `json:"user"`
}

type UserProfile struct {
	ID          string `json:"id"`
	Profile     string `json:"profile"`
	Preferences string `json:"preferences"`
}

type Meme struct {
	ID          int      `json:"id"`
	Description string   `json:"description"`
	Purpose     string   `json:"purpose"`
	Tags        []string `json:"tags"`
	SeenCount   int      `json:"seenCount"`
	UsageCount  int      `json:"usageCount"`
}

type PluginStats struct {
	TotalExtensions   int `json:"totalExtensions"`
	CmdExtensions     int `json:"cmdExtensions"`
	RouteExtensions   int `json:"routeExtensions"`
	PassiveExtensions int `json:"passiveExtensions"`
}

type HourlyMessageCount struct {
	HourLabel  string `json:"hourLabel"`
	BotCount   int    `json:"botCount"`
	GroupCount int    `json:"groupCount"`
}

type PluginRefreshResponse struct {
	Status            string            `json:"status"`
	Message           string            `json:"message"`
	RequestedPluginID string            `json:"requestedPluginId,omitempty"`
	RefreshedPlugins  []string          `json:"refreshedPlugins"`
	LoadedExtensions  int               `json:"loadedExtensions"`
	FailedPlugins     map[string]string `json:"failedPlugins"`
	HTTPStatus        int               `json:"-"`
}

type PluginCliSendRequest struct {
	Input string `json:"input"`
}

type PluginCliSendResponse struct {
	Status     string  `json:"status"`
	Message    string  `json:"message"`
	Input      string  `json:"input"`
	Echo       string  `json:"echo"`
	Reply      *string `json:"reply"`
	HTTPStatus int     `json:"-"`
}

type PluginCommandExample struct {
	PluginID      string `json:"pluginId"`
	ExtensionName string `json:"extensionName"`
	Example       string `json:"example"`
	Description   string `json:"description"`
}

type PluginCommandMatchResponse struct {
	Status     string                 `json:"status"`
	Query      string                 `json:"query"`
	Matches    []PluginCommandExample `json:"matches"`
	HTTPStatus int                    `json:"-"`
}

type ConfigRefreshResponse struct {
	Status     string                 `json:"status"`
	Message    string                 `json:"message"`
	Reloaded   ConfigReloadedSummary  `json:"reloaded"`
	Bots       BotRefreshSummary      `json:"bots"`
	Extra      map[string]interface{} `json:"-"`
	HTTPStatus int                    `json:"-"`
}

type ConfigReloadedSummary struct {
	Config bool `json:"config"`
	Roles  int  `json:"roles"`
	Rules  int  `json:"rules"`
	MCP    int  `json:"mcp"`
}

type BotRefreshSummary struct {
	Added       BotRefreshItems `json:"added"`
	Removed     BotRefreshItems `json:"removed"`
	Reconnected BotRefreshItems `json:"reconnected"`
	RoleUpdated BotRefreshItems `json:"roleUpdated"`
	Failed      BotRefreshItems `json:"failed"`
}

type BotRefreshItems []string

func (items *BotRefreshItems) UnmarshalJSON(data []byte) error {
	var names []string
	if err := json.Unmarshal(data, &names); err == nil {
		*items = names
		return nil
	}

	var count int
	if err := json.Unmarshal(data, &count); err == nil {
		*items = make([]string, count)
		return nil
	}

	return fmt.Errorf("expected bot refresh items as array or count")
}

func (items BotRefreshItems) Count() int {
	return len(items)
}

// ── Cron Tasks ──

type CronTaskRecord struct {
	TaskID         string `json:"taskId"`
	BotID          string `json:"botId"`
	GroupID        string `json:"groupId"`
	SenderID       string `json:"senderId"`
	Content        string `json:"content"`
	TriggerTime    int64  `json:"triggerTime"`
	CronExpression string `json:"cronExpression"`
	Status         string `json:"status"`
	CreatedAt      int64  `json:"createdAt"`
	FiredAt        *int64 `json:"firedAt"`
	TargetUserID   string `json:"targetUserId"`
	TaskType       string `json:"taskType"`
	TriggerType    string `json:"triggerType"`
}

type UpdateCronTaskRequest struct {
	Content        *string `json:"content,omitempty"`
	TriggerTime    *int64  `json:"triggerTime,omitempty"`
	TargetUserID   *string `json:"targetUserId,omitempty"`
	CronExpression *string `json:"cronExpression,omitempty"`
	Status         *string `json:"status,omitempty"`
}

type GroupStatus struct {
	BotID            string               `json:"botId"`
	BotName          string               `json:"botName"`
	GroupID          string               `json:"groupId"`
	GroupName        string               `json:"groupName"`
	BehaviorProfile  *BehaviorProfile     `json:"behaviorProfile"`
	PAD              *PAD                 `json:"pad"`
	FlowState        FlowState            `json:"flowState"`
	VolitionState    VolitionState        `json:"volitionState"`
	Vocabularies     []string             `json:"vocabularies"`
	Summary          *string              `json:"summary"`
	FactSize         int64                `json:"factSize"`
	UserProfileSize  int64                `json:"userProfileSize"`
	Facts            Facts                `json:"facts"`
	UserProfiles     []UserProfile        `json:"userProfiles"`
	MemeSize         int64                `json:"memeSize"`
	AnalyzedMemeSize int64                `json:"analyzedMemeSize"`
	Memes            []Meme               `json:"memes"`
	PluginStats      PluginStats          `json:"pluginStats"`
	HourlyMsgCounts  []HourlyMessageCount `json:"hourlyMsgCounts"`
}
