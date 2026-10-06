#!/usr/bin/env python3
"""Generates app/src/main/res/values{,-bn}/strings.xml from one table so both languages stay in sync.

Edit STRINGS below, then run:  python3 android/tools/gen_strings.py
"""

from __future__ import annotations

import re
from pathlib import Path

# key: (English, Bangla). Format arguments must match between the two. A dict instead of a string
# makes a <plurals> resource ({"one": ..., "other": ...}; Bangla uses the same two categories).
Text = str | dict[str, str]
STRINGS: dict[str, tuple[Text, Text]] = {
    "app_name": ("Raf Khata", "রাফ খাতা"),
    "tagline": ("Record the class. Get the notes.", "ক্লাস রেকর্ড করুন, নোট পেয়ে যান।"),
    "signin_explainer": (
        "Bangla and Banglish lectures become a transcript, notes and a study pack.",
        "বাংলা ও বাংলিশ লেকচার থেকে ট্রান্সক্রিপ্ট, নোট আর স্টাডি প্যাক।",
    ),
    "signin_google": ("Sign in with Google", "গুগল দিয়ে সাইন ইন"),
    "signin_google_not_configured": (
        "Google sign-in isn't set up in this build (set rafkhata.googleWebClientId).",
        "এই বিল্ডে গুগল সাইন-ইন সেট আপ করা নেই (rafkhata.googleWebClientId দিন)।",
    ),
    "dev_login_title": ("Developer sign-in", "ডেভেলপার সাইন-ইন"),
    "dev_login_hint": (
        "Debug builds only. Works when the server has DEV_LOGIN_ENABLED=true.",
        "শুধু ডিবাগ বিল্ডে। সার্ভারে DEV_LOGIN_ENABLED=true থাকলে কাজ করে।",
    ),
    "dev_login_button": ("Sign in as developer", "ডেভেলপার হিসেবে সাইন ইন"),
    "email": ("Email", "ইমেইল"),
    "name": ("Name", "নাম"),
    # profile
    "profile_welcome": ("Welcome!", "স্বাগতম!"),
    "profile_title": ("Profile", "প্রোফাইল"),
    "profile_onboarding_hint": (
        "Tell us a little about your studies. It helps file lectures and write notes in the right language.",
        "আপনার পড়াশোনা সম্পর্কে একটু জানান। এতে লেকচার গোছাতে আর সঠিক ভাষায় নোট লিখতে সুবিধা হয়।",
    ),
    "university": ("University", "বিশ্ববিদ্যালয়"),
    "department": ("Department", "ডিপার্টমেন্ট"),
    "batch": ("Batch", "ব্যাচ"),
    "section": ("Section", "সেকশন"),
    "notes_language": ("Notes language", "নোটের ভাষা"),
    "lang_bn": ("বাংলা", "বাংলা"),
    "lang_en": ("English", "English"),
    "lang_mixed": ("Banglish", "বাংলিশ"),
    "lang_mixed_hint": (
        "Bangla sentences with English technical terms, the way it's said in class",
        "বাংলা বাক্য, ইংরেজি টেকনিক্যাল শব্দ, ক্লাসে যেভাবে বলা হয়",
    ),
    "lang_default": ("Same as my profile", "আমার প্রোফাইলের মতো"),
    "continue_": ("Continue", "এগিয়ে যান"),
    "save": ("Save", "সেভ"),
    # common
    "back": ("Back", "পেছনে"),
    "cancel": ("Cancel", "বাতিল"),
    "ok": ("OK", "ঠিক আছে"),
    "retry": ("Retry", "আবার চেষ্টা"),
    "try_again": ("Try again", "আবার চেষ্টা করুন"),
    "delete": ("Delete", "মুছুন"),
    "edit": ("Edit", "সম্পাদনা"),
    "more": ("More options", "আরও অপশন"),
    "confirm": ("Confirm", "নিশ্চিত করুন"),
    "dismiss": ("Dismiss", "বাদ দিন"),
    "create": ("Create", "তৈরি করুন"),
    "join": ("Join", "যোগ দিন"),
    "leave": ("Leave", "ছেড়ে দিন"),
    "copy": ("Copy", "কপি"),
    "share": ("Share", "শেয়ার"),
    "send": ("Send", "পাঠান"),
    "remove": ("Remove", "সরান"),
    "show": ("Show", "দেখান"),
    "hide": ("Hide", "লুকান"),
    "next": ("Next", "পরের"),
    "previous": ("Previous", "আগের"),
    "open_settings": ("Open settings", "সেটিংস খুলুন"),
    "search": ("Search", "খুঁজুন"),
    "settings": ("Settings", "সেটিংস"),
    "version": ("Version %1$s", "ভার্সন %1$s"),
    # errors
    "error_signed_out": ("You've been signed out. Please sign in again.", "আপনি সাইন আউট হয়ে গেছেন। আবার সাইন ইন করুন।"),
    "error_forbidden": ("You don't have access to this.", "এটি দেখার অনুমতি আপনার নেই।"),
    "error_not_found": ("Not found.", "খুঁজে পাওয়া যায়নি।"),
    "error_server": ("Server problem. Please try again later.", "সার্ভারে সমস্যা। একটু পরে আবার চেষ্টা করুন।"),
    "error_request": ("Request failed", "অনুরোধ ব্যর্থ হয়েছে"),
    "error_network": ("No internet connection.", "ইন্টারনেট সংযোগ নেই।"),
    "error_unknown": ("Something went wrong", "কিছু একটা সমস্যা হয়েছে"),
    "error_google_signin": ("Google sign-in failed", "গুগল সাইন-ইন হয়নি"),
    "error_mic_permission": (
        "Raf Khata needs microphone permission to record.",
        "রেকর্ড করতে রাফ খাতার মাইক্রোফোনের অনুমতি দরকার।",
    ),
    "error_mic_unavailable": (
        "The microphone is busy or unavailable. Close other recording apps and try again.",
        "মাইক্রোফোন ব্যস্ত বা পাওয়া যাচ্ছে না। অন্য রেকর্ডিং অ্যাপ বন্ধ করে আবার চেষ্টা করুন।",
    ),
    "error_start_not_allowed": (
        "Recording can only start while Raf Khata is open on screen.",
        "রাফ খাতা স্ক্রিনে খোলা থাকলেই কেবল রেকর্ডিং শুরু করা যায়।",
    ),
    "error_recording_failed": ("Recording stopped because of an error.", "একটি সমস্যার কারণে রেকর্ডিং থেমে গেছে।"),
    # navigation
    "tab_home": ("Home", "হোম"),
    "tab_courses": ("Courses", "কোর্স"),
    "tab_sections": ("Sections", "সেকশন"),
    "tab_deadlines": ("Deadlines", "ডেডলাইন"),
    "tab_notes": ("Notes", "নোট"),
    "tab_transcript": ("Transcript", "ট্রান্সক্রিপ্ট"),
    "tab_study": ("Study", "স্টাডি"),
    # home
    "record": ("Record", "রেকর্ড"),
    "uploads": ("Uploads", "আপলোড"),
    "upcoming_deadlines": ("Upcoming deadlines", "আসন্ন ডেডলাইন"),
    "see_all": ("See all", "সব দেখুন"),
    "recent_lectures": ("Recent lectures", "সাম্প্রতিক লেকচার"),
    "no_lectures_yet": ("No lectures yet. Tap Record when your class starts.", "এখনো কোনো লেকচার নেই। ক্লাস শুরু হলে রেকর্ড চাপুন।"),
    "class_now": ("Class now", "এখন ক্লাস"),
    "class_today": ("Next class today", "আজকের পরের ক্লাস"),
    "class_tomorrow": ("Next class tomorrow", "আগামীকালের ক্লাস"),
    "interrupted_title": ("Recording interrupted", "রেকর্ডিং মাঝপথে থেমে গেছে"),
    "interrupted_text": (
        "%1$s: %2$s was saved before the app stopped. Upload it?",
        "%1$s: অ্যাপ বন্ধ হওয়ার আগে %2$s সেভ হয়েছে। আপলোড করবেন?",
    ),
    "upload": ("Upload", "আপলোড"),
    "upload_now": ("Upload now", "এখনই আপলোড"),
    "upload_uploading": ("Uploading %1$d%%", "আপলোড হচ্ছে %1$d%%"),
    "upload_waiting_wifi": ("Waiting for Wi-Fi", "Wi-Fi-এর অপেক্ষায়"),
    "upload_waiting_network": ("Waiting for internet", "ইন্টারনেটের অপেক্ষায়"),
    "upload_failed": ("Upload failed: %1$s", "আপলোড হয়নি: %1$s"),
    "upload_failed_title": ("Upload failed", "আপলোড হয়নি"),
    "upload_other_account": (
        "Recorded while signed in to another account. Sign in with that account to upload it.",
        "অন্য অ্যাকাউন্টে থাকার সময় রেকর্ড করা। আপলোড করতে সেই অ্যাকাউন্টে সাইন ইন করুন।",
    ),
    "delete_recording_title": ("Delete recording?", "রেকর্ডিং মুছবেন?"),
    "delete_recording_text": (
        "This recording hasn't been uploaded. It will be removed from your phone.",
        "এই রেকর্ডিং এখনো আপলোড হয়নি। এটি ফোন থেকে মুছে যাবে।",
    ),
    "battery_tip_title": ("Keep recordings running", "রেকর্ডিং যেন বন্ধ না হয়"),
    "battery_tip_text": (
        "Some phones stop background apps to save battery. Turn off battery optimization for Raf Khata so a "
        "90-minute class records without a break.",
        "অনেক ফোন ব্যাটারি বাঁচাতে ব্যাকগ্রাউন্ডের অ্যাপ বন্ধ করে দেয়। রাফ খাতার জন্য ব্যাটারি অপটিমাইজেশন বন্ধ "
        "করুন, যাতে ৯০ মিনিটের ক্লাসও পুরোটা রেকর্ড হয়।",
    ),
    # lecture status
    "status_uploading": ("Uploading", "আপলোড হচ্ছে"),
    "status_queued": ("Waiting to be processed", "প্রসেসিংয়ের অপেক্ষায়"),
    "status_processing": ("Processing…", "প্রসেস হচ্ছে…"),
    "status_ready": ("Ready", "তৈরি"),
    "status_failed": ("Processing failed", "প্রসেসিং ব্যর্থ হয়েছে"),
    "step_assemble": ("Preparing the audio…", "অডিও প্রস্তুত হচ্ছে…"),
    "step_transcribe": ("Transcribing…", "ট্রান্সক্রাইব হচ্ছে…"),
    "step_correct": ("Checking technical terms…", "টেকনিক্যাল শব্দ যাচাই হচ্ছে…"),
    "step_notes": ("Writing the notes…", "নোট লেখা হচ্ছে…"),
    "step_study": ("Making the study pack…", "স্টাডি প্যাক তৈরি হচ্ছে…"),
    "untitled_lecture": ("Lecture", "লেকচার"),
    "recorded_by": ("by %1$s", "রেকর্ড: %1$s"),
    # courses
    "add_course": ("Add course", "কোর্স যোগ করুন"),
    "edit_course": ("Edit course", "কোর্স সম্পাদনা"),
    "no_courses": (
        "Add your courses and class routine so recordings are filed automatically.",
        "কোর্স আর ক্লাস রুটিন যোগ করুন, তাহলে রেকর্ডিং নিজে থেকেই সঠিক কোর্সে যাবে।",
    ),
    "archived_courses": ("Archived (%1$d)", "আর্কাইভ করা (%1$d)"),
    "archive": ("Archive", "আর্কাইভ করুন"),
    "unarchive": ("Unarchive", "আর্কাইভ থেকে ফেরান"),
    "lecture_count": (
        {"one": "%1$d lecture", "other": "%1$d lectures"},
        {"one": "%1$dটি লেকচার", "other": "%1$dটি লেকচার"},
    ),
    "shared_with_section": ("Shared with %1$s", "%1$s সেকশনের সাথে শেয়ার করা"),
    "only_me": ("Only me", "শুধু আমি"),
    "notes_in": ("Notes in %1$s", "নোটের ভাষা: %1$s"),
    "routine": ("Class routine", "ক্লাস রুটিন"),
    "routine_hint": (
        "When you record during these times, the lecture is filed under this course automatically.",
        "এই সময়ে রেকর্ড করলে লেকচারটি নিজে থেকেই এই কোর্সে যাবে।",
    ),
    "glossary": ("Course terms", "কোর্সের পরিভাষা"),
    "glossary_hint": (
        "Technical words and names the teacher uses, separated by commas. They help the transcript spell them right.",
        "শিক্ষক যেসব টেকনিক্যাল শব্দ ও নাম বলেন, কমা দিয়ে আলাদা করে লিখুন। এতে ট্রান্সক্রিপ্টে বানান ঠিক হয়।",
    ),
    "consent_confirmed": ("Teacher's permission to record confirmed", "রেকর্ড করার জন্য শিক্ষকের অনুমতি নিশ্চিত করা হয়েছে"),
    "consent_needed": (
        "Confirm that the teacher allows recording before you record this course.",
        "এই কোর্স রেকর্ড করার আগে নিশ্চিত করুন যে শিক্ষক অনুমতি দিয়েছেন।",
    ),
    "lectures": ("Lectures", "লেকচার"),
    "no_lectures_in_course": ("No lectures recorded for this course yet.", "এই কোর্সে এখনো কোনো লেকচার রেকর্ড হয়নি।"),
    "delete_course": ("Delete course", "কোর্স মুছুন"),
    "delete_course_text": (
        "The course and its routine are deleted. Its lectures stay in your list without a course.",
        "কোর্স আর রুটিন মুছে যাবে। এর লেকচারগুলো কোর্স ছাড়াই থেকে যাবে।",
    ),
    "course_title": ("Course title", "কোর্সের নাম"),
    "course_code": ("Course code (e.g. CSE 2201)", "কোর্স কোড (যেমন CSE 2201)"),
    "teacher_name": ("Teacher", "শিক্ষক"),
    "semester": ("Semester", "সেমিস্টার"),
    "share_with": ("Who can see its lectures", "এর লেকচার কারা দেখতে পাবে"),
    "share_with_hint": (
        "Pick a section to share this course's lectures with your classmates.",
        "ক্লাসমেটদের সাথে এই কোর্সের লেকচার শেয়ার করতে একটি সেকশন বেছে নিন।",
    ),
    "add_class_time": ("Add class time", "ক্লাসের সময় যোগ করুন"),
    "end_after_start": ("The class must end after it starts.", "ক্লাস শেষের সময় শুরুর পরে হতে হবে।"),
    "room": ("Room", "রুম"),
    # sections
    "sections_explainer": (
        "One recording for the whole class: lectures in a section's courses are shared with all its members.",
        "পুরো ক্লাসের জন্য একটি রেকর্ডিং: সেকশনের কোর্সে রেকর্ড করা লেকচার সব সদস্যের সাথে শেয়ার হয়।",
    ),
    "join_section": ("Join with code", "কোড দিয়ে যোগ দিন"),
    "join_section_hint": (
        "Ask your CR or a classmate for the section's invite code.",
        "সেকশনের ইনভাইট কোড আপনার CR বা ক্লাসমেটের কাছ থেকে নিন।",
    ),
    "create_section": ("Create section", "সেকশন তৈরি করুন"),
    "create_section_hint": (
        "You become the owner. Share the invite code with classmates.",
        "আপনি হবেন মালিক। ক্লাসমেটদের সাথে ইনভাইট কোড শেয়ার করুন।",
    ),
    "section_name": ("Section name", "সেকশনের নাম"),
    "section_label": ("Section (e.g. CSE-22 B)", "সেকশন (যেমন CSE-22 B)"),
    "no_sections": ("You're not in a section yet.", "আপনি এখনো কোনো সেকশনে নেই।"),
    "member_count": (
        {"one": "%1$d member", "other": "%1$d members"},
        {"one": "%1$d জন সদস্য", "other": "%1$d জন সদস্য"},
    ),
    "members_count": ("Members (%1$d)", "সদস্য (%1$d)"),
    "member_me": ("%1$s (you)", "%1$s (আপনি)"),
    "role_owner": ("Owner", "মালিক"),
    "role_cr": ("CR", "CR"),
    "role_member": ("Member", "সদস্য"),
    "make_cr": ("Make CR", "CR বানান"),
    "make_member": ("Make regular member", "সাধারণ সদস্য করুন"),
    "invite_code": ("Invite code", "ইনভাইট কোড"),
    "invite_hint": (
        "Classmates who join with this code see the section's lectures and notes.",
        "এই কোড দিয়ে যোগ দিলে ক্লাসমেটরা সেকশনের লেকচার ও নোট দেখতে পাবে।",
    ),
    "invite_message": (
        "Join our section \"%1$s\" on Raf Khata with invite code %2$s",
        "রাফ খাতায় আমাদের সেকশন \"%1$s\"-এ যোগ দাও, ইনভাইট কোড: %2$s",
    ),
    "new_code": ("New invite code", "নতুন ইনভাইট কোড"),
    "leave_section": ("Leave section", "সেকশন ছেড়ে দিন"),
    "leave_section_text": (
        "You'll stop seeing this section's lectures. You can join again with the invite code.",
        "এই সেকশনের লেকচার আর দেখতে পাবেন না। ইনভাইট কোড দিয়ে আবার যোগ দিতে পারবেন।",
    ),
    # record
    "record_title": ("Record class", "ক্লাস রেকর্ড"),
    "course": ("Course", "কোর্স"),
    "no_course": ("No course", "কোনো কোর্স নয়"),
    "lecture_topic_optional": ("Topic (optional)", "টপিক (ঐচ্ছিক)"),
    "consent_checkbox": ("The teacher has allowed me to record this class", "শিক্ষক এই ক্লাস রেকর্ড করার অনুমতি দিয়েছেন"),
    "consent_already_confirmed": (
        "You've confirmed the teacher's permission for this course.",
        "এই কোর্সে শিক্ষকের অনুমতি আপনি নিশ্চিত করেছেন।",
    ),
    "sound_check": ("Sound check", "সাউন্ড চেক"),
    "sound_check_run": ("Check", "চেক করুন"),
    "sound_check_again": ("Check again", "আবার চেক"),
    "sound_check_hint": (
        "Listens for 8 seconds to check noise and volume where you're sitting.",
        "আপনি যেখানে বসেছেন সেখানে শব্দ আর আওয়াজ কেমন, ৮ সেকেন্ড শুনে দেখে।",
    ),
    "sound_check_listening": ("Listening…", "শোনা হচ্ছে…"),
    "sound_good": ("Sounds good. Ready to record.", "শব্দ ভালো আছে। রেকর্ড করতে পারেন।"),
    "sound_tip_quiet": (
        "Too quiet: move closer to the teacher and don't cover the microphone.",
        "আওয়াজ কম: শিক্ষকের কাছে বসুন, মাইক্রোফোন ঢেকে রাখবেন না।",
    ),
    "sound_tip_noisy": (
        "Noisy: move away from the fan or closer to the teacher.",
        "আশপাশে শব্দ বেশি: ফ্যান থেকে দূরে বা শিক্ষকের কাছে বসুন।",
    ),
    "sound_tip_clipping": (
        "Too loud: move the phone a little away from the speaker.",
        "খুব জোরে: ফোন স্পিকার থেকে একটু দূরে সরান।",
    ),
    "placement_tips_title": ("For a clear recording", "পরিষ্কার রেকর্ডিংয়ের জন্য"),
    "placement_tips": (
        "Sit near the teacher, put the phone screen-up on the desk and keep it away from the fan. "
        "The screen can be off while recording.",
        "শিক্ষকের কাছে বসুন, ফোন স্ক্রিন উপরে রেখে টেবিলে রাখুন, আর ফ্যান থেকে দূরে রাখুন। "
        "রেকর্ডিংয়ের সময় স্ক্রিন বন্ধ রাখা যাবে।",
    ),
    "start_recording": ("Start recording", "রেকর্ডিং শুরু করুন"),
    "recording_starting": ("Starting…", "শুরু হচ্ছে…"),
    "recording_in_progress": ("Recording", "রেকর্ড হচ্ছে"),
    "recording_paused": ("Paused", "বিরতিতে"),
    "recording_saving": ("Saving…", "সেভ হচ্ছে…"),
    "recording_partly_saved": (
        "What was recorded so far is saved and will be uploaded.",
        "এ পর্যন্ত যা রেকর্ড হয়েছে তা সেভ হয়েছে এবং আপলোড হবে।",
    ),
    "bookmark_important": ("Important", "গুরুত্বপূর্ণ"),
    "bookmark_confused": ("Didn't get it", "বুঝিনি"),
    "bookmark_mic_silenced": ("Microphone was paused by a call", "কলের কারণে মাইক্রোফোন বন্ধ ছিল"),
    "bookmark_count": (
        {"one": "%1$d bookmark", "other": "%1$d bookmarks"},
        {"one": "%1$dটি বুকমার্ক", "other": "%1$dটি বুকমার্ক"},
    ),
    "photo_count": (
        {"one": "%1$d photo", "other": "%1$d photos"},
        {"one": "%1$dটি ছবি", "other": "%1$dটি ছবি"},
    ),
    "board_photo": ("Board photo", "বোর্ডের ছবি"),
    "board_photos": ("Board photos", "বোর্ডের ছবি"),
    "no_camera_app": ("No camera app found.", "কোনো ক্যামেরা অ্যাপ পাওয়া যায়নি।"),
    "mic_silenced": ("Microphone paused by a call or another app", "কল বা অন্য অ্যাপের কারণে মাইক্রোফোন বন্ধ"),
    "pause": ("Pause", "বিরতি"),
    "resume": ("Resume", "আবার শুরু"),
    "stop": ("Stop", "থামান"),
    "play": ("Play", "চালান"),
    "stop_recording_title": ("Stop recording?", "রেকর্ডিং থামাবেন?"),
    "stop_recording_text": (
        "The recording will be saved and uploaded for notes.",
        "রেকর্ডিং সেভ হবে এবং নোটের জন্য আপলোড হবে।",
    ),
    "screen_off_hint": (
        "You can turn the screen off. Recording continues in the background.",
        "স্ক্রিন বন্ধ করতে পারেন। রেকর্ডিং ব্যাকগ্রাউন্ডে চলতে থাকবে।",
    ),
    # lecture
    "export_pdf": ("Save as PDF", "PDF হিসেবে সেভ"),
    "rate_notes": ("Rate these notes", "নোটের রেটিং দিন"),
    "report_problem": ("Report a problem", "সমস্যা জানান"),
    "report_hint": (
        "What's wrong? For example: wrong content, a missing part, a mistake in a formula.",
        "সমস্যাটা কী? যেমন: ভুল তথ্য, কোনো অংশ বাদ পড়েছে, সূত্রে ভুল।",
    ),
    "comment_optional": ("Comment (optional)", "মন্তব্য (ঐচ্ছিক)"),
    "feedback_thanks": ("Thanks for the feedback!", "মতামতের জন্য ধন্যবাদ!"),
    "delete_lecture": ("Delete lecture", "লেকচার মুছুন"),
    "delete_lecture_text": (
        "The recording, transcript and notes are deleted for everyone in the section.",
        "রেকর্ডিং, ট্রান্সক্রিপ্ট আর নোট সেকশনের সবার জন্য মুছে যাবে।",
    ),
    "processing_hint": ("You'll get a notification when the notes are ready.", "নোট তৈরি হলে নোটিফিকেশন পাবেন।"),
    "notes_being_written": (
        "Writing the notes… This usually takes a few minutes.",
        "নোট লেখা হচ্ছে… সাধারণত কয়েক মিনিট লাগে।",
    ),
    "notes_missing": ("No notes in this language yet.", "এই ভাষায় এখনো নোট নেই।"),
    "generate_notes_in": ("Make notes in %1$s", "নোট তৈরি করুন: %1$s"),
    "notes_ready": ("Notes ready", "নোট তৈরি"),
    "audio_not_ready": ("The audio isn't available yet.", "অডিও এখনো পাওয়া যাচ্ছে না।"),
    "audio_error": ("Couldn't play the audio. Tap play to try again.", "অডিও চালানো যায়নি। আবার চেষ্টা করতে প্লে চাপুন।"),
    "transcript_not_ready": (
        "The transcript appears here once the lecture is processed.",
        "লেকচার প্রসেস হলে এখানে ট্রান্সক্রিপ্ট দেখাবে।",
    ),
    "transcript_saved": ("Correction saved", "সংশোধন সেভ হয়েছে"),
    "transcript_save_failed": ("Couldn't save the correction", "সংশোধন সেভ করা যায়নি"),
    "edit_line": ("Correct this line", "লাইনটি ঠিক করুন"),
    "edit_line_hint": (
        "Fix what was misheard. Keep English words in English letters.",
        "ভুল শোনা অংশ ঠিক করুন। ইংরেজি শব্দ ইংরেজি অক্ষরেই রাখুন।",
    ),
    "speaker_teacher": ("Teacher", "শিক্ষক"),
    "speaker_student": ("Student", "শিক্ষার্থী"),
    "play_from": ("Play from %1$s", "%1$s থেকে শুনুন"),
    "study_being_made": ("Making the study pack…", "স্টাডি প্যাক তৈরি হচ্ছে…"),
    "study_missing": ("No study pack in this language yet.", "এই ভাষায় এখনো স্টাডি প্যাক নেই।"),
    "flashcards_count": ("Flashcards (%1$d)", "ফ্ল্যাশকার্ড (%1$d)"),
    "quiz_count": ("Quiz (%1$d)", "কুইজ (%1$d)"),
    "questions_count": ("Questions (%1$d)", "প্রশ্ন (%1$d)"),
    "nothing_here": ("Nothing here for this lecture.", "এই লেকচারে এখানে কিছু নেই।"),
    "tap_to_flip": ("Tap the card to flip it", "কার্ড উল্টাতে ট্যাপ করুন"),
    "question_n_of": ("Question %1$d of %2$d", "প্রশ্ন %1$d / %2$d"),
    "correct": ("Correct!", "সঠিক!"),
    "not_quite": ("Not quite", "সঠিক হয়নি"),
    "see_score": ("See score", "স্কোর দেখুন"),
    "quiz_score": ("Score: %1$d / %2$d", "স্কোর: %1$d / %2$d"),
    "short_question": ("Short question", "সংক্ষিপ্ত প্রশ্ন"),
    "broad_question": ("Broad question", "রচনামূলক প্রশ্ন"),
    "show_answer": ("Show answer points", "উত্তরের মূল পয়েন্ট দেখুন"),
    "hide_answer": ("Hide answer", "উত্তর লুকান"),
    # deadlines
    "no_deadlines": (
        "No deadlines yet. Announcements made in lectures show up here.",
        "এখনো কোনো ডেডলাইন নেই। লেকচারে দেওয়া ঘোষণা এখানে দেখাবে।",
    ),
    "upcoming": ("Upcoming", "আসন্ন"),
    "past": ("Past", "আগের"),
    "date_not_clear": ("Date not clear", "তারিখ স্পষ্ট নয়"),
    "due_today": ("today", "আজ"),
    "due_tomorrow": ("tomorrow", "আগামীকাল"),
    "due_in_days": (
        {"one": "in %1$d day", "other": "in %1$d days"},
        {"one": "%1$d দিন পর", "other": "%1$d দিন পর"},
    ),
    "reminder_on": ("Reminder on", "রিমাইন্ডার চালু"),
    "reminder_off": ("Reminder off", "রিমাইন্ডার বন্ধ"),
    "reminders_off_hint": ("Reminders are off. Turn them on in Settings.", "রিমাইন্ডার বন্ধ আছে। সেটিংস থেকে চালু করুন।"),
    "reminder_due_today": ("Due today", "আজই শেষ সময়"),
    "reminder_due_tomorrow": ("Due tomorrow", "আগামীকাল শেষ সময়"),
    "kind_ct": ("CT", "CT"),
    "kind_quiz": ("Quiz", "কুইজ"),
    "kind_assignment": ("Assignment", "অ্যাসাইনমেন্ট"),
    "kind_exam": ("Exam", "পরীক্ষা"),
    "kind_presentation": ("Presentation", "প্রেজেন্টেশন"),
    "kind_lab": ("Lab", "ল্যাব"),
    "kind_class_change": ("Class change", "ক্লাস পরিবর্তন"),
    "kind_other": ("Other", "অন্যান্য"),
    # search
    "search_hint": ("Search lectures", "লেকচারে খুঁজুন"),
    "search_explainer": ("Search all your transcripts and notes.", "আপনার সব ট্রান্সক্রিপ্ট আর নোটে খুঁজুন।"),
    "no_results": ("Nothing found.", "কিছু পাওয়া যায়নি।"),
    # settings
    "account": ("Account", "অ্যাকাউন্ট"),
    "app_language": ("App language", "অ্যাপের ভাষা"),
    "language_system": ("Phone's language", "ফোনের ভাষা"),
    "language_bangla": ("বাংলা", "বাংলা"),
    "language_english": ("English", "English"),
    "recording_and_upload": ("Recording and upload", "রেকর্ডিং ও আপলোড"),
    "wifi_only": ("Upload on Wi-Fi only", "শুধু Wi-Fi-তে আপলোড"),
    "wifi_only_hint": (
        "Saves mobile data. About 15 MB per hour of class.",
        "মোবাইল ডেটা বাঁচায়। প্রতি ঘণ্টার ক্লাসে প্রায় ১৫ MB।",
    ),
    "mic_source": ("Microphone", "মাইক্রোফোন"),
    "mic_voice_recognition": ("Speech (recommended)", "কথার জন্য (প্রস্তাবিত)"),
    "mic_default": ("Phone microphone", "ফোনের মাইক্রোফোন"),
    "mic_camcorder": ("Video microphone", "ভিডিও মাইক্রোফোন"),
    "mic_unprocessed": ("Raw, without phone processing (if supported)", "ফোনের প্রসেসিং ছাড়া (সাপোর্ট করলে)"),
    "battery_setting": ("Battery optimization", "ব্যাটারি অপটিমাইজেশন"),
    "battery_setting_hint": (
        "Set Raf Khata to \"Not optimized\" so long recordings aren't stopped.",
        "লম্বা রেকর্ডিং যেন বন্ধ না হয়, তাই রাফ খাতার ব্যাটারি অপটিমাইজেশন বন্ধ করুন।",
    ),
    "notifications": ("Notifications", "নোটিফিকেশন"),
    "deadline_reminders": ("Deadline reminders", "ডেডলাইন রিমাইন্ডার"),
    "deadline_reminders_hint": (
        "The evening before a CT, quiz or assignment is due",
        "CT, কুইজ বা অ্যাসাইনমেন্টের আগের সন্ধ্যায়",
    ),
    "allow_notifications": ("Allow notifications", "নোটিফিকেশন চালু করুন"),
    "allow_notifications_hint": (
        "Needed for \"notes ready\" alerts and deadline reminders.",
        "\"নোট তৈরি\" অ্যালার্ট আর ডেডলাইন রিমাইন্ডারের জন্য দরকার।",
    ),
    "push_not_configured": (
        "Push notifications aren't set up in this build.",
        "এই বিল্ডে পুশ নোটিফিকেশন সেট আপ করা নেই।",
    ),
    "privacy_and_data": ("Privacy and data", "গোপনীয়তা ও ডেটা"),
    "privacy_summary": (
        "Recordings are private to you, or shared only with the section you choose. "
        "Record only with the teacher's permission.",
        "রেকর্ডিং শুধু আপনার, অথবা আপনার বেছে নেওয়া সেকশনের সাথে শেয়ার হয়। শুধু শিক্ষকের অনুমতি নিয়েই রেকর্ড করুন।",
    ),
    "export_data": ("Export my data", "আমার ডেটা এক্সপোর্ট করুন"),
    "export_data_hint": (
        "Save everything stored about you as a JSON file.",
        "আপনার সম্পর্কে সংরক্ষিত সব তথ্য একটি JSON ফাইলে সেভ করুন।",
    ),
    "export_done": ("Your data was exported.", "আপনার ডেটা এক্সপোর্ট হয়েছে।"),
    "sign_out": ("Sign out", "সাইন আউট"),
    "sign_out_text": ("You can sign in again any time.", "যেকোনো সময় আবার সাইন ইন করতে পারবেন।"),
    "sign_out_unsent": (
        {
            "one": "%1$d recording hasn't been uploaded yet and will be deleted from this phone.",
            "other": "%1$d recordings haven't been uploaded yet and will be deleted from this phone.",
        },
        {
            "one": "%1$dটি রেকর্ডিং এখনো আপলোড হয়নি, সেটি এই ফোন থেকে মুছে যাবে।",
            "other": "%1$dটি রেকর্ডিং এখনো আপলোড হয়নি, সেগুলো এই ফোন থেকে মুছে যাবে।",
        },
    ),
    "delete_account": ("Delete account", "অ্যাকাউন্ট মুছুন"),
    "delete_account_hint": ("Removes your account, recordings and notes.", "আপনার অ্যাকাউন্ট, রেকর্ডিং আর নোট মুছে যাবে।"),
    "delete_account_text": (
        "Your account and every lecture you recorded, with its transcript and notes, are deleted permanently, "
        "also for your sections.",
        "আপনার অ্যাকাউন্ট এবং আপনার রেকর্ড করা সব লেকচার, ট্রান্সক্রিপ্ট ও নোটসহ, চিরতরে মুছে যাবে, "
        "আপনার সেকশনের জন্যও।",
    ),
    # notification channels
    "channel_recording": ("Recording", "রেকর্ডিং"),
    "channel_uploads": ("Uploads", "আপলোড"),
    "channel_notes": ("Notes ready", "নোট তৈরি"),
    "channel_reminders": ("Deadline reminders", "ডেডলাইন রিমাইন্ডার"),
}

ARG = re.compile(r"%(\d+\$)?[sd]")


def escape(text: str) -> str:
    text = text.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text


def render(index: int) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by android/tools/gen_strings.py. Edit that file. -->",
        "<resources>",
    ]
    for key, values in STRINGS.items():
        if index == 1 and key in untranslatable():
            continue
        value = values[index]
        if isinstance(value, dict):
            lines.append(f'    <plurals name="{key}">')
            for quantity, text in value.items():
                lines.append(f'        <item quantity="{quantity}">{escape(text)}</item>')
            lines.append("    </plurals>")
        else:
            attrs = ' translatable="false"' if index == 0 and key in untranslatable() else ""
            lines.append(f'    <string name="{key}"{attrs}>{escape(value)}</string>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main() -> None:
    for key, (en, bn) in STRINGS.items():
        if isinstance(en, dict) != isinstance(bn, dict):
            raise SystemExit(f"{key!r} must be a plural in both languages or in neither")
        en_texts = list(en.values()) if isinstance(en, dict) else [en]
        bn_texts = list(bn.values()) if isinstance(bn, dict) else [bn]
        args = {tuple(sorted(ARG.findall(t))) for t in en_texts + bn_texts}
        if len(args) != 1:
            raise SystemExit(f"format arguments differ for {key!r}")
    res = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
    (res / "values").mkdir(parents=True, exist_ok=True)
    (res / "values-bn").mkdir(parents=True, exist_ok=True)
    (res / "values" / "strings.xml").write_text(render(0), encoding="utf-8")
    (res / "values-bn" / "strings.xml").write_text(render(1), encoding="utf-8")
    print(f"wrote {len(STRINGS)} strings")


def untranslatable() -> list[str]:
    """Language names, which read the same in both files."""
    return [k for k, (en, bn) in STRINGS.items() if en == bn and k.startswith("lang")]


if __name__ == "__main__":
    main()
