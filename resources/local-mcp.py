from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.server import Settings
from typing import Annotated
from pydantic import Field

mcp = FastMCP("Weather", port=9000)

@mcp.tool(description="获取天气")
async def get_weather(Location: Annotated[str, Field(description="城市，例如：北京")]) -> str:
    """
    Get weather for Location.

    Args:
        Location (str): Location to get weather for.
    Returns:
        str: Weather for Location.
    """
    return "今天晴天"

if __name__ == "__main__":
#     mcp.run(transport="stdio")
#     mcp.run(transport="sse")
    mcp.run(transport="streamable-http")
