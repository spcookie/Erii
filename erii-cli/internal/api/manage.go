package api

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

const defaultFetchLimit = 10000

func paginatedURL(path string) string {
	return fmt.Sprintf("%s?offset=0&limit=%d", path, defaultFetchLimit)
}

func (c *Client) GetBots() ([]BotInfo, error) {
	return doJSONRequest[[]BotInfo](c, "GET", "/api/bots", nil)
}

func (c *Client) GetGroups(botID string) ([]GroupInfo, error) {
	return doJSONRequest[[]GroupInfo](c, "GET", fmt.Sprintf("/api/bot/%s/groups", botID), nil)
}

func (c *Client) GetFacts(botID, groupID string) (PaginatedResponse[FactRecord], error) {
	return doJSONRequest[PaginatedResponse[FactRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/facts", botID, groupID)), nil)
}

func (c *Client) GetFact(botID, groupID string, id int) (*FactRecord, error) {
	return doJSONRequest[*FactRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/facts/%d", botID, groupID, id), nil)
}

func (c *Client) CreateFact(botID, groupID string, req FactRequest) error {
	_, err := c.doRequest("POST", fmt.Sprintf("/api/bot/%s/group/%s/facts", botID, groupID), req)
	return err
}

func (c *Client) UpdateFact(botID, groupID string, id int, req FactRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/facts/%d", botID, groupID, id), req)
	return err
}

func (c *Client) DeleteFact(botID, groupID string, id int) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/facts/%d", botID, groupID, id), nil)
	return err
}

func (c *Client) SearchMemoryVector(botID, groupID string, req MemorySearchRequest) (*MemoryVectorSearchResponse, error) {
	return doJSONRequest[*MemoryVectorSearchResponse](c, "POST", fmt.Sprintf("/api/bot/%s/group/%s/facts/vector-search", botID, groupID), req)
}

func (c *Client) SearchMemoryGraph(botID, groupID string, req MemorySearchRequest) (*MemoryGraphSearchResponse, error) {
	return doJSONRequest[*MemoryGraphSearchResponse](c, "POST", fmt.Sprintf("/api/bot/%s/group/%s/facts/graph-search", botID, groupID), req)
}

func (c *Client) GetUserProfiles(botID, groupID string) (PaginatedResponse[UserProfileRecord], error) {
	return doJSONRequest[PaginatedResponse[UserProfileRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/user-profiles", botID, groupID)), nil)
}

func (c *Client) GetUserProfile(botID, groupID, userID string) (*UserProfileRecord, error) {
	return doJSONRequest[*UserProfileRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/user-profiles/%s", botID, groupID, userID), nil)
}

func (c *Client) UpdateUserProfile(botID, groupID, userID string, req UpdateUserProfileRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/user-profiles/%s", botID, groupID, userID), req)
	return err
}

func (c *Client) DeleteUserProfile(botID, groupID, userID string) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/user-profiles/%s", botID, groupID, userID), nil)
	return err
}

func (c *Client) GetMemes(botID, groupID string) (PaginatedResponse[MemeRecord], error) {
	return doJSONRequest[PaginatedResponse[MemeRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/memes", botID, groupID)), nil)
}

func (c *Client) GetMeme(botID, groupID string, id int) (*MemeRecord, error) {
	return doJSONRequest[*MemeRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/memes/%d", botID, groupID, id), nil)
}

func (c *Client) UpdateMeme(botID, groupID string, id int, req UpdateMemeRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/memes/%d", botID, groupID, id), req)
	return err
}

func (c *Client) DeleteMeme(botID, groupID string, id int) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/memes/%d", botID, groupID, id), nil)
	return err
}

func (c *Client) SearchMemeVector(botID, groupID string, req MemeSearchRequest) (*MemeVectorSearchResponse, error) {
	return doJSONRequest[*MemeVectorSearchResponse](c, "POST", fmt.Sprintf("/api/bot/%s/group/%s/memes/vector-search", botID, groupID), req)
}

func (c *Client) GetVocabularies(botID, groupID string) (PaginatedResponse[VocabRecord], error) {
	return doJSONRequest[PaginatedResponse[VocabRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/vocabulary", botID, groupID)), nil)
}

func (c *Client) GetVocabulary(botID, groupID string, id int) (*VocabRecord, error) {
	return doJSONRequest[*VocabRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/vocabulary/%d", botID, groupID, id), nil)
}

func (c *Client) CreateVocabulary(botID, groupID string, req VocabRequest) error {
	_, err := c.doRequest("POST", fmt.Sprintf("/api/bot/%s/group/%s/vocabulary", botID, groupID), req)
	return err
}

func (c *Client) UpdateVocabulary(botID, groupID string, id int, req VocabRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/vocabulary/%d", botID, groupID, id), req)
	return err
}

func (c *Client) DeleteVocabulary(botID, groupID string, id int) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/vocabulary/%d", botID, groupID, id), nil)
	return err
}

func (c *Client) GetSummaries(botID, groupID string) (PaginatedResponse[SummaryRecord], error) {
	return doJSONRequest[PaginatedResponse[SummaryRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/summaries", botID, groupID)), nil)
}

func (c *Client) GetSummary(botID, groupID string, id int) (*SummaryRecord, error) {
	return doJSONRequest[*SummaryRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/summaries/%d", botID, groupID, id), nil)
}

func (c *Client) UpdateSummary(botID, groupID string, id int, req UpdateSummaryRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/summaries/%d", botID, groupID, id), req)
	return err
}

func (c *Client) DeleteSummary(botID, groupID string, id int) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/summaries/%d", botID, groupID, id), nil)
	return err
}

func (c *Client) GetHistory(botID, groupID string) (PaginatedResponse[HistoryRecord], error) {
	return doJSONRequest[PaginatedResponse[HistoryRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/history", botID, groupID)), nil)
}

func (c *Client) GetHistoryByID(botID, groupID string, id int) (*HistoryRecord, error) {
	return doJSONRequest[*HistoryRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/history/%d", botID, groupID, id), nil)
}

func (c *Client) UpdateHistory(botID, groupID string, id int, req UpdateHistoryRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/history/%d", botID, groupID, id), req)
	return err
}

func (c *Client) DeleteHistory(botID, groupID string, id int) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/history/%d", botID, groupID, id), nil)
	return err
}

func (c *Client) GetResources(botID, groupID string) (PaginatedResponse[ResourceRecord], error) {
	return doJSONRequest[PaginatedResponse[ResourceRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/resources", botID, groupID)), nil)
}

func (c *Client) GetEmotion(botID, groupID string) (*EmotionRecord, error) {
	return doJSONRequest[*EmotionRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/emotion", botID, groupID), nil)
}

func (c *Client) UpdateEmotion(botID, groupID string, req UpdateEmotionRequest) (*EmotionRecord, error) {
	return doJSONRequest[*EmotionRecord](c, "PUT", fmt.Sprintf("/api/bot/%s/group/%s/emotion", botID, groupID), req)
}

func (c *Client) GetFlow(botID, groupID string) (*FlowRecord, error) {
	return doJSONRequest[*FlowRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/flow", botID, groupID), nil)
}

func (c *Client) UpdateFlow(botID, groupID string, req UpdateFlowRequest) (*FlowRecord, error) {
	return doJSONRequest[*FlowRecord](c, "PUT", fmt.Sprintf("/api/bot/%s/group/%s/flow", botID, groupID), req)
}

func (c *Client) GetVolition(botID, groupID string) (*VolitionRecord, error) {
	return doJSONRequest[*VolitionRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/volition", botID, groupID), nil)
}

func (c *Client) UpdateVolition(botID, groupID string, req UpdateVolitionRequest) (*VolitionRecord, error) {
	return doJSONRequest[*VolitionRecord](c, "PUT", fmt.Sprintf("/api/bot/%s/group/%s/volition", botID, groupID), req)
}

func (c *Client) GetCronTasks(botID, groupID string) (PaginatedResponse[CronTaskRecord], error) {
	return doJSONRequest[PaginatedResponse[CronTaskRecord]](c, "GET", paginatedURL(fmt.Sprintf("/api/bot/%s/group/%s/cron-tasks", botID, groupID)), nil)
}

func (c *Client) GetCronTask(botID, groupID, taskID string) (*CronTaskRecord, error) {
	return doJSONRequest[*CronTaskRecord](c, "GET", fmt.Sprintf("/api/bot/%s/group/%s/cron-tasks/%s", botID, groupID, taskID), nil)
}

func (c *Client) UpdateCronTask(botID, groupID, taskID string, req UpdateCronTaskRequest) error {
	_, err := c.doRequest("PUT", fmt.Sprintf("/api/bot/%s/group/%s/cron-tasks/%s", botID, groupID, taskID), req)
	return err
}

func (c *Client) DeleteCronTask(botID, groupID, taskID string) error {
	_, err := c.doRequest("DELETE", fmt.Sprintf("/api/bot/%s/group/%s/cron-tasks/%s", botID, groupID, taskID), nil)
	return err
}

func (c *Client) GetUsage(botID, groupID string) (*TokenUsageSummary, error) {
	path := "/api/usage"
	if botID != "" || groupID != "" {
		var params []string
		if botID != "" {
			params = append(params, "botId="+botID)
		}
		if groupID != "" {
			params = append(params, "groupId="+groupID)
		}
		path += "?" + strings.Join(params, "&")
	}
	return doJSONRequest[*TokenUsageSummary](c, "GET", path, nil)
}

func (c *Client) RefreshConfig() (*ConfigRefreshResponse, error) {
	data, statusCode, err := c.doRequestWithStatus("POST", "/api/config/refresh", nil)
	if err != nil {
		return nil, err
	}

	var result ConfigRefreshResponse
	if err := json.Unmarshal(data, &result); err != nil {
		if statusCode >= 400 {
			return nil, fmt.Errorf("HTTP %d: %s", statusCode, string(data))
		}
		return nil, err
	}
	result.HTTPStatus = statusCode
	return &result, nil
}

func (c *Client) RefreshPlugins(pluginID string) (*PluginRefreshResponse, error) {
	path := "/api/plugins/refresh"
	if strings.TrimSpace(pluginID) != "" {
		path = fmt.Sprintf("/api/plugins/%s/refresh", url.PathEscape(pluginID))
	}
	data, statusCode, err := c.doRequestWithStatus("POST", path, nil)
	if err != nil {
		return nil, err
	}

	var result PluginRefreshResponse
	if err := json.Unmarshal(data, &result); err != nil {
		if statusCode >= 400 {
			return nil, fmt.Errorf("HTTP %d: %s", statusCode, string(data))
		}
		return nil, err
	}
	result.HTTPStatus = statusCode
	if statusCode >= 400 {
		return &result, fmt.Errorf("HTTP %d: %s", statusCode, result.Message)
	}
	return &result, nil
}

func (c *Client) SendPluginCli(input string) (*PluginCliSendResponse, error) {
	data, statusCode, err := c.doRequestWithStatus("POST", "/api/plugins/cli/send", PluginCliSendRequest{Input: input})
	if err != nil {
		return nil, err
	}

	var result PluginCliSendResponse
	if err := json.Unmarshal(data, &result); err != nil {
		if statusCode >= 400 {
			return nil, fmt.Errorf("HTTP %d: %s", statusCode, string(data))
		}
		return nil, err
	}
	result.HTTPStatus = statusCode
	if statusCode >= 400 {
		return &result, fmt.Errorf("HTTP %d: %s", statusCode, result.Message)
	}
	return &result, nil
}

func (c *Client) MatchPluginCommands(query string, limit int) (*PluginCommandMatchResponse, error) {
	if limit <= 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	path := fmt.Sprintf("/api/plugins/cli/match?query=%s&limit=%d", url.QueryEscape(query), limit)
	data, statusCode, err := c.doRequestWithStatus("GET", path, nil)
	if err != nil {
		return nil, err
	}

	var result PluginCommandMatchResponse
	if err := json.Unmarshal(data, &result); err != nil {
		if statusCode >= 400 {
			return nil, fmt.Errorf("HTTP %d: %s", statusCode, string(data))
		}
		return nil, err
	}
	result.HTTPStatus = statusCode
	if statusCode >= 400 {
		return &result, fmt.Errorf("HTTP %d", statusCode)
	}
	return &result, nil
}
