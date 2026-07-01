#!/usr/bin/env python3
import os
import json
import shutil
import re
import sys

# Minimal ANSI Colors for TUI
class C:
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BOLD = '\033[1m'
    DIM = '\033[2m'
    RESET = '\033[0m'
    CLEAR = '\033[2J\033[H'

def init_ansi():
    if os.name == 'nt':
        os.system('') # Enables ANSI on modern Windows

def clear_screen():
    sys.stdout.write(C.CLEAR)
    sys.stdout.flush()

def prompt(text, desc="", default=""):
    print(f"{C.CYAN}{C.BOLD}❯ {text}{C.RESET}")
    if desc:
        print(f"  {C.DIM}{desc}{C.RESET}")
    if default:
        val = input(f"  {C.YELLOW}[{default}]{C.RESET} ➔  ").strip()
    else:
        val = input(f"  ➔  ").strip()
    print("") # spacing
    return val if val else default

def print_step(msg):
    print(f"{C.BOLD}{C.YELLOW}⟳{C.RESET} {msg}")

def print_success(msg):
    print(f"{C.BOLD}{C.GREEN}✓{C.RESET} {msg}")

def print_warn(msg):
    print(f"{C.BOLD}{C.YELLOW}⚠{C.RESET} {msg}")

def print_error(msg):
    print(f"{C.BOLD}{C.RED}✗{C.RESET} {msg}")

def replace_in_file(filepath, old_str, new_str):
    if not os.path.exists(filepath):
        return
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    content = content.replace(old_str, new_str)
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

def main():
    init_ansi()
    clear_screen()
    
    # Banner
    print(f"{C.CYAN}{C.BOLD}")
    print("╭──────────────────────────────────────────────╮")
    print("│         OUTREACH AGENT SETUP CLI 🤖          │")
    print("╰──────────────────────────────────────────────╯")
    print(f"{C.RESET}\n{C.DIM}Let's configure your personal details.{C.RESET}\n")

    name = prompt("Full Name")
    email = prompt("Email Address")
    phone = prompt("Phone Number")
    linkedin = prompt("LinkedIn Profile URL")
    github = prompt("GitHub Profile URL")
    location = prompt("Location", "e.g., City, State")
    portfolio = prompt("Portfolio URL")
    role = prompt("Target Role", "e.g., Machine Learning Engineer")

    clear_screen()
    print(f"{C.CYAN}{C.BOLD}Applying Configuration...{C.RESET}\n")

    # 1. Update master_resume.json
    print_step("Updating master_resume.json...")
    resume_path = "src/main/resources/data/master_resume.json"
    if os.path.exists(resume_path):
        with open(resume_path, 'r', encoding='utf-8') as f:
            try:
                resume_data = json.load(f)
                info = resume_data.get("personalInfo", {})
                if name: info["name"] = name
                if email: info["email"] = email
                if phone: info["phone"] = phone
                if linkedin: info["linkedin"] = linkedin
                if github: info["github"] = github
                if location: info["location"] = location
                with open(resume_path, 'w', encoding='utf-8') as fw:
                    json.dump(resume_data, fw, indent=2)
                print_success("Updated master_resume.json")
            except Exception as e:
                print_error(f"Failed to parse master_resume.json: {e}")
    else:
        print_warn(f"{resume_path} not found.")

    # 2. Setup .env
    print_step("Checking environment variables...")
    env_file = ".env"
    env_example = ".env.example"
    
    if not os.path.exists(env_file) and os.path.exists(env_example):
        shutil.copy(env_example, env_file)
        print_success(f"Created {env_file} from template")
    
    if os.path.exists(env_file):
        with open(env_file, 'r', encoding='utf-8') as f:
            env_content = f.read()
        
        if email:
            env_content = re.sub(r'GMAIL_ADDRESS=.*', f'GMAIL_ADDRESS={email}', env_content)
        if role:
            env_content = re.sub(r'DISCOVERY_ROLE=.*', f'DISCOVERY_ROLE={role}', env_content)
            
        with open(env_file, 'w', encoding='utf-8') as f:
            f.write(env_content)
        print_success("Updated .env file")

    # 3. Code Modifications
    print_step("Updating Java services...")
    controller_path = "src/main/java/com/outreach/agent/controller/BatchOutreachController.java"
    if email:
        replace_in_file(controller_path, "your_test_email@gmail.com", email)

    service_path = "src/main/java/com/outreach/agent/service/BatchOutreachService.java"
    if portfolio:
        replace_in_file(service_path, "https://yourportfolio.com", portfolio)
    
    print_success("Injected details into Java source files")

    print(f"\n{C.GREEN}{C.BOLD}🎉 Setup complete!{C.RESET}")
    print(f"\n{C.DIM}Next steps:{C.RESET}")
    print(f"  {C.CYAN}1.{C.RESET} Fill out your API keys in the {C.YELLOW}.env{C.RESET} file")
    print(f"  {C.CYAN}2.{C.RESET} Run {C.YELLOW}'mvn spring-boot:run'{C.RESET} to start the backend")
    print(f"  {C.CYAN}3.{C.RESET} Run {C.YELLOW}'npm run dev'{C.RESET} inside the frontend/ directory to start the UI\n")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n{C.RED}Setup cancelled.{C.RESET}")
