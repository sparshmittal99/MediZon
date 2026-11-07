package com.medizon.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The daily health quote sent to customers' phones: which quote, and at what time, for each day. Both are worked out
 * from the date alone, so they stay the same across restarts. The quotes come in a fixed shuffled order, one per day,
 * so no quote comes back until all of them have been sent ({@link #count()} days). Times are between
 * {@link #EARLIEST} and {@link #LATEST}.
 */
public final class HealthQuotes {

    public static final LocalTime EARLIEST = LocalTime.of(7, 0);
    /** Sent no later than this; a day whose time has passed by then (the server was asleep) is skipped. */
    public static final LocalTime LATEST = LocalTime.of(21, 0);

    private static final List<String> QUOTES = List.of(
            "Take care of your body. It's the only place you have to live.",
            "A glass of water first thing in the morning is a small gift to your whole day.",
            "Health is not just the absence of illness; it is the energy to enjoy your life.",
            "Small steps every day add up to a healthier you.",
            "Sleep is not a luxury. It is the time your body repairs itself.",
            "Eat food that comes from a plant, not food that was made in a plant.",
            "A 10-minute walk is better than no walk at all.",
            "Your body hears everything your mind says. Speak kindly to yourself.",
            "Rest when you need to, but never give up on your health.",
            "Good health is the first wealth.",
            "Laughter is a medicine with no side effects.",
            "Fill half your plate with vegetables, and your body will thank you.",
            "Take your medicines on time. Consistency is half the cure.",
            "Every healthy choice you make today is an investment in tomorrow.",
            "Stretch for five minutes. Your back and shoulders will feel the difference.",
            "Deep breaths calm the mind: breathe in for four, hold for four, out for four.",
            "Wash your hands well. It's one of the simplest ways to stay healthy.",
            "Movement is medicine. Find a way to move your body today.",
            "A healthy outside starts from the inside.",
            "Don't wait for a health scare to start caring for your health.",
            "Drink water before you feel thirsty; thirst is a late signal.",
            "Your future self will thank you for the healthy habits you start today.",
            "Fresh fruit is nature's dessert.",
            "Taking the stairs is a free workout waiting for you.",
            "Mental health is health too. It's okay to ask for help.",
            "Progress, not perfection, is what keeps you healthy.",
            "A calm mind brings inner strength and better health.",
            "Get some morning sunlight. It helps your sleep, mood and vitamin D.",
            "Less salt, less sugar, more life.",
            "Chew slowly and enjoy your food; your digestion begins in your mouth.",
            "Regular check-ups catch small problems before they become big ones.",
            "Healthy habits are learned the same way unhealthy ones are: through practice.",
            "Put your phone away an hour before bed and let your mind slow down.",
            "Kindness is good for your heart, in more ways than one.",
            "You don't have to be extreme, just consistent.",
            "Strong body, calm mind, happy heart.",
            "A good laugh and a long sleep are the best cures in the doctor's book.",
            "Eat a rainbow: different coloured vegetables bring different nutrients.",
            "Your health is an investment, not an expense.",
            "Posture check: sit tall, relax your shoulders, and breathe.",
            "When you feel like quitting, remember why you started.",
            "An apple a day is good, but a balanced diet every day is better.",
            "Take a break from the screen: every 20 minutes, look 20 feet away for 20 seconds.",
            "Health is a relationship between you and your body. Nurture it.",
            "The best time to start was yesterday. The next best time is now.",
            "Walking after meals helps keep your blood sugar steady.",
            "Nourish your body, it's the only one you get.",
            "Gratitude is a quiet medicine for the mind.",
            "Don't skip breakfast; give your body fuel to start the day.",
            "Listen to your body. It whispers before it shouts.",
            "Spend time in nature. Even a few minutes outside can lift your mood.",
            "Smile more. It's good for you and for everyone around you.",
            "A healthy family is a happy family. Encourage each other.",
            "Keep your medicines in a cool, dry place and check their expiry dates.",
            "Your body achieves what your mind believes.",
            "Snack smart: a handful of nuts beats a packet of chips.",
            "It's not about having time, it's about making time for your health.",
            "Rest is productive. Recovery is part of progress.",
            "Health is a daily practice, not a one-time fix.",
            "Cut down on fried food; your heart works hard enough already.",
            "A healthy mind lives in a healthy body.",
            "Today, be a little kinder to yourself than you were yesterday.",
            "Drink a glass of water with every meal.",
            "Dance, play, walk, cycle: exercise doesn't have to feel like exercise.",
            "Good sleep, good food, good company: the simple recipe for good health.",
            "Keep moving. Sitting still for too long is hard on the body.",
            "Your health is worth more than any deadline.",
            "Choose whole grains over refined ones for lasting energy.",
            "A little sunshine, a little exercise, a lot of water.",
            "The greatest wealth is health.",
            "Take a moment today to simply breathe and be still.",
            "Prevention is better than cure.",
            "Treat your body like it belongs to someone you love.",
            "Strong bones need calcium, vitamin D and movement.",
            "Write down three things you're grateful for today.",
            "Health is a crown that only the sick can see.",
            "Early to bed and early to rise makes a person healthy and wise.",
            "If you're tired, rest. Don't quit.",
            "Eat to nourish your body, not just to fill your stomach.",
            "Every step counts. Literally.",
            "Protect your ears: keep headphone volume moderate.",
            "Stress less, live more. Let go of what you can't control.",
            "A balanced plate makes a balanced body.",
            "Sip water through the day instead of all at once.",
            "Your health is your responsibility, and your power.",
            "A good friend and a long walk can fix more than you'd think.",
            "Make your health a priority, not an afterthought.",
            "Even on busy days, five minutes of movement is possible.",
            "Too much screen, too little sleep: switch it around tonight.",
            "Feeling low? Talk to someone. Sharing lightens the load.",
            "Choose fruit over fruit juice: the fibre keeps you full and healthy.",
            "Brush twice a day and floss once. Oral health is whole-body health.",
            "Be patient with yourself. Healing takes time.",
            "The food you eat can be the safest medicine or the slowest poison.",
            "Wake up, stretch, hydrate, and let the day begin.",
            "Healthy is a lifestyle, not a diet.",
            "A positive mind finds a way; a healthy body follows.",
            "Keep a water bottle with you as a reminder to drink.",
            "Your heart loves it when you walk, swim, cycle or dance.",
            "Don't compare your health journey with anyone else's.",
            "Quiet time is not wasted time. It recharges you.",
            "Cook at home more often; you know exactly what goes in.",
            "Health begins with small, wise choices.",
            "Sleep at the same time each night; your body loves a routine.",
            "Take care of your mind, and your body will follow.",
            "Fresh air and an open window can brighten your whole day.",
            "Eat slowly. Your brain needs about 20 minutes to know you're full.",
            "Celebrate small wins on your health journey.",
            "Healthy habits today, fewer doctor visits tomorrow.",
            "Peace of mind is part of good health. Protect it.",
            "Add a handful of greens to one meal today.",
            "Your body is your home for life. Keep it in good repair.",
            "A cup of herbal tea and a quiet moment can do wonders.",
            "Never skip a prescribed course of medicine halfway, even if you feel better.",
            "Balance is the key: work, rest, play, repeat.",
            "Wellness is the sum of many small, good days.",
            "Smile at a stranger today. Good feelings are contagious.",
            "Stay curious, stay active, stay young at heart.",
            "Health is happiness in motion.",
            "Love yourself enough to live a healthy life.");

    /** The quotes in the order they're sent: shuffled once, the same way every time. */
    private static final List<String> ORDER;

    static {
        List<String> order = new ArrayList<>(QUOTES);
        Collections.shuffle(order, new Random(20261107L));
        ORDER = List.copyOf(order);
    }

    private HealthQuotes() {}

    /** How many quotes there are, so how many days pass before one comes back. */
    public static int count() { return ORDER.size(); }

    /** The quote for {@code day}: a different one each day, none repeated within {@link #count()} days. */
    public static String forDay(LocalDate day) {
        return ORDER.get(Math.floorMod(day.toEpochDay(), ORDER.size()));
    }

    /** The time it goes out on {@code day}: different each day, between 7:00 and 20:59. */
    public static LocalTime sendTime(LocalDate day) {
        int minutes = (int) java.time.Duration.between(EARLIEST, LATEST).toMinutes();
        Random random = new Random(day.toEpochDay() * 0x9E3779B97F4A7C15L + 17);
        return EARLIEST.plusMinutes(random.nextInt(minutes));
    }
}
