"""Training phrases for the intent classifier, per role. Add phrases here to teach the assistant new wording."""

SHARED = {
    "greet": [
        "hi", "hello", "hey", "hey there", "good morning", "good evening", "good afternoon", "namaste", "hii",
        "hello assistant", "hi medizon", "yo", "hola", "hey bot",
    ],
    "thanks": [
        "thanks", "thank you", "thanks a lot", "thank you so much", "great thanks", "awesome thank you",
        "that helps", "perfect thanks", "cool thanks", "thx", "ty", "much appreciated", "dhanyavad", "shukriya",
    ],
    "bye": [
        "bye", "goodbye", "see you", "see ya", "that's all", "that is all", "nothing else", "i'm done",
        "close chat", "talk later", "good night", "exit",
    ],
    "help": [
        "help", "what can you do", "how can you help", "what do you do", "show me options", "menu",
        "what are your features", "how does this work", "what can i ask you", "options", "commands",
        "can you help me", "i need help", "guide me",
    ],
    "cancel": [
        "cancel", "stop", "never mind", "nevermind", "forget it", "start over", "reset", "leave it",
        "cancel that", "abort", "no stop", "don't do that",
    ],
    "set_general_reminder": [
        "remind me to call the doctor tomorrow at 5 pm", "remind me to upload my prescription tomorrow",
        "remind me in 2 hours to check my sugar", "set a reminder for my doctor appointment on friday at 11 am",
        "remind me about my blood test tomorrow morning", "can you remind me to drink water in 30 minutes",
        "remind me at 6 pm to follow up", "reminder for appointment next monday", "remind me tomorrow",
        "set a reminder to restock paracetamol tomorrow at 10 am", "remind me to check pending orders at 4 pm",
        "remind me to call the supplier on monday", "create a reminder for stock audit on 15 oct",
        "add a reminder to review prescriptions in 1 hour", "remind me to reply to reviews tonight",
    ],
    "list_general_reminders": [
        "show my reminders", "what reminders do i have", "list my reminders", "my upcoming reminders",
        "any reminders pending", "what did i ask you to remind me", "pending reminders", "my to do list",
        "show my tasks reminders", "upcoming reminders",
    ],
}

CUSTOMER = {
    "search_medicine": [
        "do you have paracetamol", "search for cetirizine", "find metformin", "i'm looking for vitamin d",
        "show me cough syrup", "is azithromycin available", "search crocin", "find medicine for fever",
        "something for headache", "what do you have for acidity", "medicine for cold and cough",
        "i need something for allergy", "show diabetes medicines", "do you sell thermometers",
        "looking for multivitamin", "search ibuprofen 400", "find pantoprazole", "show me blood pressure tablets",
        "paracetamol", "dolo 650", "need omega 3", "any medicine for loose motion", "do you stock ors",
        "find glucometer strips", "what medicines do you have for thyroid", "browse vitamins",
        "show me painkillers", "i have a fever what can i take", "i have a headache", "my stomach hurts",
        "suggest something for sore throat", "alternatives to dolo", "cheapest paracetamol",
    ],
    "medicine_info": [
        "what is paracetamol used for", "tell me about metformin", "side effects of cetirizine",
        "how should i take azithromycin", "what is amlodipine", "info about vitamin d3",
        "is ibuprofen safe", "uses of pantoprazole", "how does atorvastatin work", "what does levothyroxine do",
        "details of telmisartan", "can i take cetirizine at night", "precautions for glimepiride",
        "what should i know before taking ibuprofen", "explain omega 3 benefits", "dosage of paracetamol",
        "does azithromycin need prescription", "price of metformin", "how much does cetirizine cost",
    ],
    "add_to_cart": [
        "add paracetamol to cart", "add 2 packs of cetirizine", "buy metformin", "i want to buy vitamin d",
        "order 3 strips of pantoprazole", "put ibuprofen in my cart", "add it to cart", "add this to my cart",
        "add the first one", "add two of those", "buy the second one", "add 1 pack", "i'll take it",
        "get me ors sachets", "add omega 3 to my basket", "purchase azithromycin", "order dolo 650",
        "add to cart", "add one more", "buy this", "add the cheapest one",
    ],
    "view_cart": [
        "show my cart", "what's in my cart", "view cart", "open my cart", "cart", "my basket",
        "how much is my cart", "cart total", "what did i add", "check my cart", "items in cart", "go to cart",
        "what's in my bag", "show my bag", "what is left in my basket", "my shopping bag",
    ],
    "checkout": [
        "checkout", "check out", "proceed to checkout", "take me to checkout", "place my order", "i want to pay",
        "pay now", "complete my order", "go to payment", "buy now", "confirm my order", "finish order",
        "place order", "i'm ready to order", "let's checkout",
    ],
    "track_order": [
        "where is my order", "track my order", "order status", "my orders", "when will my order arrive",
        "has my order shipped", "show my recent orders", "is my order delivered", "track order mz",
        "status of my last order", "did my order ship", "order history", "my last order", "delivery status",
        "when does my parcel come", "where is my parcel", "when will it be delivered", "is my package on the way",
        "how long for delivery", "has my delivery been dispatched",
    ],
    "set_reminder": [
        "remind me to take metformin at 8 am", "set a reminder for cetirizine at night",
        "set medicine reminder", "add a dose reminder", "remind me to take my bp tablet twice a day",
        "i want reminders for vitamin d", "create a reminder for paracetamol every 6 hours",
        "remind me to take my medicine", "set alarm for my tablets", "schedule my medicine",
        "add medicine to my schedule", "reminder for thyroid tablet every morning",
        "set up dose reminders", "remind me daily to take omega 3 after dinner", "new medicine reminder",
        "track my medicine doses", "help me remember my pills",
    ],
    "my_reminders": [
        "show my medicines", "my medication plans", "what medicines am i taking", "list my reminders for medicines",
        "my medicine schedule", "how many days of supply do i have", "which medicine is running low",
        "when will my tablets run out", "my plans", "how many tablets left", "show my dose schedule",
        "which medicines do i take", "supply left",
    ],
    "todays_doses": [
        "what doses today", "today's doses", "what should i take today", "my doses for today",
        "did i take my medicine today", "what's my next dose", "next dose", "today's schedule",
        "which tablets are due now", "what's due", "pending doses", "which pills now", "what pills do i take now",
        "do i have any dose now", "what's my schedule today",
    ],
    "mark_dose": [
        "i took my metformin", "i have taken my medicine", "mark dose as taken", "taken", "i took it",
        "done took my tablet", "skip this dose", "i skipped my dose", "mark as skipped", "i missed my dose",
        "already took cetirizine", "i took my morning pills", "skip metformin today",
    ],
    "refill": [
        "refill my metformin", "reorder my medicines", "i'm running low", "order my usual medicines",
        "refill", "repeat my last refill", "reorder cetirizine", "buy my regular medicine again",
        "i need a refill", "refill the medicine that's running out", "reorder low medicines",
        "my sugar medicine is finishing", "my tablets are about to finish", "i am running out of my bp tablets",
        "almost out of medicine", "order more of my regular tablets",
    ],
    "upload_prescription": [
        "upload prescription", "how do i upload my prescription", "i want to upload a prescription",
        "add my prescription", "where do i send my prescription", "prescription upload",
        "my doctor gave me a prescription", "submit prescription", "is my prescription approved",
        "how do i send my doctor's note", "send the doctor slip", "where to attach rx", "upload rx",
        "prescription status",
    ],
    "feedback": [
        "i want to give feedback", "feedback", "i have a complaint", "complaint", "report a problem",
        "the delivery was late", "my order arrived damaged", "your app is great", "i have a suggestion",
        "suggestion for you", "the packaging was bad", "customer service was rude", "i want to complain",
        "write a review", "rate your service", "share feedback", "the website is slow",
        "received wrong medicine", "love this app",
    ],
    "navigate": [
        "open my profile", "go to my profile", "change my address", "open notifications",
        "go to shop", "open the shop", "go home", "open home page", "update my phone number",
        "take me to my medicines page", "open prescriptions page", "show notifications",
    ],
    "policy": [
        "what is your return policy", "return policy", "can i return medicines", "how do i return an item",
        "how do refunds work", "when will i get my refund", "refund policy", "where is my refund",
        "what are the delivery charges", "is delivery free", "delivery fee", "how much is shipping",
        "how can i pay", "payment options", "do you accept upi", "can i pay cash on delivery", "do you take cards",
        "can i cancel my order", "how do i cancel an order", "cancel my order", "cancellation policy",
        "what are your policies", "store policy",
    ],
    "pharmacist": [
        "talk to a pharmacist", "can i speak to someone", "contact support", "customer care number",
        "i want to talk to a human", "call me", "speak to pharmacist", "contact you", "support email",
        "need a doctor", "consult a doctor",
    ],
}

ADMIN = {
    "review_insights": [
        "analyze reviews", "what are customers saying", "review insights", "summarize customer feedback",
        "how can we improve", "suggestions for improvement", "read customer reviews", "analyse feedback",
        "what are the complaints", "why are ratings low", "customer sentiment", "improvement ideas",
        "what do people complain about", "what are people unhappy about", "how are the reviews looking",
        "what do customers like", "top complaints", "review summary", "feedback report",
        "give me suggestions from reviews", "what should we fix",
    ],
    "store_tasks": [
        "what needs my attention", "pending tasks", "what should i do today", "today's tasks", "to do",
        "what's pending", "any pending orders", "orders to confirm", "store status", "daily summary",
        "dashboard summary", "give me an overview", "how is the store doing", "pending prescriptions",
        "orders to ship", "what's waiting", "anything urgent", "morning briefing", "status update",
    ],
    "low_stock": [
        "low stock", "what's running out", "which medicines are low on stock", "items to restock",
        "restock list", "out of stock items", "stock alerts", "inventory low", "what should i reorder from supplier",
        "stock running low",
    ],
    "stock_check": [
        "how much paracetamol stock", "stock of cetirizine", "do we have metformin in stock",
        "check stock for vitamin d", "how many packs of ors left", "inventory of ibuprofen",
        "is azithromycin in stock", "stock level of pantoprazole", "find medicine in catalog",
        "search catalog for omega 3",
    ],
    "complete_reminder": [
        "mark reminder done", "done with that reminder", "complete reminder", "reminder done",
        "finish the reminder", "clear my reminder", "dismiss reminder", "i did it mark it done",
    ],
    "navigate": [
        "open orders", "go to orders page", "open prescriptions", "show catalog", "open users",
        "go to reviews", "open dashboard", "take me to the catalog", "add a new medicine",
        "manage users", "open customer orders", "go to prescriptions page",
    ],
}


def training_data(role: str) -> tuple[list[str], list[str]]:
    table = dict(SHARED)
    table.update(ADMIN if role == "admin" else CUSTOMER)
    texts, labels = [], []
    for intent, phrases in table.items():
        for p in phrases:
            texts.append(p)
            labels.append(intent)
    return texts, labels
