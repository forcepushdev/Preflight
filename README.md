# Preflight (Local Code Review, IntelliJ Plugin)

> **Alpha** - works, has rough edges, used daily by the author.

---

## Review your agents code locally

No push, no additional server.

---

## The Problem

You're working with AI agents. 
Claude builds a feature, refactors something, adds a layer. 
A few prompts later you have 300 changed lines across five files, six commits, and no idea what was actually changed.

Now, when reviewing the code you can choose:
- push to origin and do a PR in github/azure etc.
- write comments in the code 
- write all comments in a file or promt, referencing the files

That's not how I want do do that. I don't want to push this slop to main to do a review and then maybe copy my comments again.
I also don't want to remove 20 comments when I'm done.
And I don't want to reference the files in a promt.

There's no good solution.
So I built one.

---
![img.png](img.png)
## What This Plugin Does

Leave comments directly in your editor without touching your code.

- Mark any line or block of code
- Annotate it with your thoughts, questions or concerns
- Reply to annotations, mark them as resolved
- Everything is saved locally as JSON, no server, no cloud, no account

Your codebase stays clean. Your reviews stay yours.

### !! You currently have to commit to be able to see the changes in Preflight !!

---



## Why local JSON?

Because I don't want to push to some server, I don't wanted to push to origin to make a PR in github or azure.

I just want this to be between me and the agent.

The JSON format is designed to be readable by AI agents. Hook it up to your agent, just explain the format and handover the json.
(Claude code PLUGIN planned)
---

## Installation

Plugin Marketplace release planned.
For now, build from source:

```bash
git clone https://github.com/forcepushdev/Preflight.git
cd Preflight
./gradlew buildPlugin
```

Then in IntelliJ: `Settings -> Plugins -> Install Plugin from Disk`

---

## Status

This is alpha software. It works, I use it every day, but expect rough edges.
Updates may and WILL break your comments.

Known issues:
- Branch switching can cause annotation offsets
- Line numbers shift when code changes after annotations are added

Currently there is no agent plugin, I use this prompt:
```
you got a new review in .preflight/comments.json, the format is                                                                
[                                         
  {                                        
    "file": "FILEPATH",                                             
    "line": INT,                                
    "comment": "REVIEWER COMMENT",              
    "resolved": false,                         
    "replies": [                                
      "HERE IS A REPLY, you can put your reply here. Maybe there is a conversation already "                                     
    ],                                          
    "startLine": INT                            
  }                                             
]                                                                                                                                
startline is the line where the comment starts, endline where it ends.
please solve the review commands, if you think the comment is wrong answer in the replies.                                       
Replies is a list of replies {"text" :"THE TEXT", "author": "author"} use author AGENT.                                          
When you solved the comments reply with "solved", do not set the resolved property.                                                                                
if the line numbers need update because you changed files, update them. 
```

---

## Roadmap
- [ ] Comment without commit
- [ ] Claude Code Plugin
- [ ] VS Code Extension
- [ ] branch switching support
- [ ] IntelliJ Marketplace Plugin

---

## Contributing

Found a bug? Have an idea? 
Open an issue or a PR, this is early and feedback shapes where it goes.

---

## Why I Built This
Because I want to review my agents code locally.

I vibe-coded this plugin.
The irony isn't lost on me. I built a review tool to for my production code by vibe coding XD.

---
## Plugin stuff, no NOT remove!

<!-- Plugin description -->
Review your AI Agents changes locally.


This specific section is a source for the [plugin.xml](/src/main/resources/META-INF/plugin.xml) file which will be extracted by the [Gradle](/build.gradle.kts) during the build process.

To keep everything working, do not remove `<!-- ... -->` sections.
<!-- Plugin description end -->
