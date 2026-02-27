from flask import Flask, request, jsonify
from datetime import datetime

from browser_use import Agent, Browser, ChatBrowserUse
from dotenv import load_dotenv
import asyncio

load_dotenv()

async def browserVoodoo(task):
    browser = Browser(headless=True)    
    agent = Agent(task=task, browser=browser, llm=ChatBrowserUse())
    history = await agent.run()
    return history.final_result()


app = Flask(__name__)

def log(method, path, body=None, params=None):
    ts = datetime.now().strftime("%H:%M:%S")
    print(f"\n[{ts}] {method} {path}")
    if params:
        print(f"  Query Params : {dict(params)}")
    if body:
        print(f"  Body         : {body}")
    print("-" * 40)

# ── Generic catch-all for any path & method ───────────────────────────────────
@app.route("/", defaults={"path": ""}, methods=["GET","POST","PUT","PATCH","DELETE"])
@app.route("/<path:path>",             methods=["GET","POST","PUT","PATCH","DELETE"])
def catch_all(path):
    body = request.get_json(silent=True) or request.form.to_dict() or None
    log(request.method, request.path, body=body, params=request.args)

    if request.method == "POST":
        return jsonify({"browser-result":asyncio.run(browserVoodoo(body['message']))}), 200
       

if __name__ == "__main__":
    print("🚀  Flask REST API running on http://localhost:5000")
    print("    All requests are logged to this console.\n")
    app.run(host="0.0.0.0", debug=True, port=5000)
