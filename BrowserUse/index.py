from browser_use import Agent, ChatBrowserUse
from dotenv import load_dotenv
import asyncio

load_dotenv()

async def main():
    llm = ChatBrowserUse()
    task = "Find the number 1 post on Show HN"
    agent = Agent(task=task, llm=llm)
    history = await agent.run()
    return history.final_result()

if __name__ == "__main__":
    print("BROWSER-USE")
    print(asyncio.run(main()))