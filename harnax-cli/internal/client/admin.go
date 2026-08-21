package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"
)

type AdminClient struct {
	BaseURL        string
	Token          string
	InternalSecret string
	HTTPClient     *http.Client
	Verbose        bool
}

func NewAdminClient(baseURL, token string) *AdminClient {
	return &AdminClient{
		BaseURL: baseURL,
		Token:   token,
		HTTPClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

func NewAdminClientWithSecret(baseURL, internalSecret string) *AdminClient {
	return &AdminClient{
		BaseURL:        baseURL,
		InternalSecret: internalSecret,
		HTTPClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

type ResultVo struct {
	Code      int             `json:"code"`
	Message   string          `json:"message"`
	Data      json.RawMessage `json:"data"`
	Timestamp int64           `json:"timestamp"`
}

func (r *ResultVo) IsSuccess() bool {
	return r.Code == 200
}

func (r *ResultVo) DecodeData(v any) error {
	if r.Data == nil {
		return nil
	}
	return json.Unmarshal(r.Data, v)
}

type Page struct {
	List     json.RawMessage `json:"list"`
	Records  json.RawMessage `json:"records"`
	Total    int             `json:"total"`
	PageNum  int             `json:"pageNum"`
	PageSize int             `json:"pageSize"`
}

func (p *Page) DecodeList(v any) error {
	data := p.Records
	if data == nil {
		data = p.List
	}
	if data == nil {
		return nil
	}
	return json.Unmarshal(data, v)
}

type APIError struct {
	Code    int
	Message string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("API error (code: %d): %s", e.Code, e.Message)
}

func (c *AdminClient) List(ctx context.Context, path string, params map[string]string) (*ResultVo, error) {
	return c.request(ctx, http.MethodGet, path, params, nil)
}

func (c *AdminClient) Get(ctx context.Context, path string, id any) (*ResultVo, error) {
	return c.request(ctx, http.MethodGet, fmt.Sprintf("%s/%v", path, id), nil, nil)
}

func (c *AdminClient) Create(ctx context.Context, path string, body any) (*ResultVo, error) {
	return c.request(ctx, http.MethodPost, path, nil, body)
}

func (c *AdminClient) Update(ctx context.Context, path string, id any, body any) (*ResultVo, error) {
	return c.request(ctx, http.MethodPut, fmt.Sprintf("%s/%v", path, id), nil, body)
}

func (c *AdminClient) Delete(ctx context.Context, path string, id any) (*ResultVo, error) {
	return c.request(ctx, http.MethodDelete, fmt.Sprintf("%s/%v", path, id), nil, nil)
}

func (c *AdminClient) Toggle(ctx context.Context, path string, id any, status *int) (*ResultVo, error) {
	target := 1
	if status != nil {
		target = *status
	} else {
		result, err := c.Get(ctx, path, id)
		if err != nil {
			return nil, err
		}
		var entity struct {
			Status int `json:"status"`
		}
		if err := result.DecodeData(&entity); err != nil {
			return nil, err
		}
		if entity.Status == 1 {
			target = 0
		}
	}
	params := map[string]string{"status": fmt.Sprintf("%d", target)}
	return c.request(ctx, http.MethodPut, fmt.Sprintf("%s/toggle/%v", path, id), params, nil)
}

func (c *AdminClient) Request(ctx context.Context, method, path string, params map[string]string, body any) (*ResultVo, error) {
	return c.request(ctx, method, path, params, body)
}

func (c *AdminClient) request(ctx context.Context, method, path string, params map[string]string, body any) (*ResultVo, error) {
	u, err := url.Parse(c.BaseURL + path)
	if err != nil {
		return nil, fmt.Errorf("invalid URL: %w", err)
	}

	if len(params) > 0 {
		q := u.Query()
		for k, v := range params {
			q.Set(k, v)
		}
		u.RawQuery = q.Encode()
	}

	var bodyReader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("marshal body: %w", err)
		}
		bodyReader = bytes.NewReader(data)
	}

	req, err := http.NewRequestWithContext(ctx, method, u.String(), bodyReader)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if c.InternalSecret != "" {
		req.Header.Set("Authorization", "Bearer "+c.InternalSecret)
	} else if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "→ %s %s\n", method, u.String())
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if c.Verbose {
		fmt.Fprintf(os.Stderr, "← %d %s\n", resp.StatusCode, string(respBody))
	}

	if resp.StatusCode == http.StatusUnauthorized {
		return nil, &APIError{Code: 401, Message: "Authentication expired. Please run 'harnax login' to re-authenticate."}
	}

	var result ResultVo
	if err := json.Unmarshal(respBody, &result); err != nil {
		return nil, fmt.Errorf("decode response: %w (body: %s)", err, string(respBody))
	}

	if !result.IsSuccess() {
		return &result, &APIError{Code: result.Code, Message: result.Message}
	}

	return &result, nil
}
