#!/bin/bash
# ============================================
# Humanizer 全面测试脚本
# ============================================

BASE="http://localhost:8080/v1/humanizer"

# 2000+ 词长文本（存到变量里）
read -r -d '' LONG_TEXT << 'LONGEOF'
The rapid advancement of artificial intelligence has fundamentally transformed numerous aspects of modern society, creating both unprecedented opportunities and significant challenges that demand careful consideration from researchers, policymakers, and the general public alike. From healthcare to education, from finance to entertainment, AI systems are increasingly being deployed to automate tasks, enhance decision-making processes, and create new possibilities that were previously unimaginable. The scope and scale of this transformation continue to accelerate, driven by improvements in computational power, the availability of massive datasets, and breakthroughs in algorithmic design that have enabled machines to perform tasks once thought to be exclusively within the domain of human intelligence.

In the field of healthcare, machine learning algorithms are being used to analyze medical images with remarkable accuracy, often surpassing the diagnostic capabilities of experienced physicians who have spent decades honing their skills. These systems can detect subtle patterns in X-rays, MRI scans, CT images, and pathology slides that might escape human observation, potentially leading to earlier detection of diseases such as cancer, cardiovascular conditions, and neurological disorders. Furthermore, AI-powered drug discovery platforms are accelerating the development of new therapeutic compounds, potentially reducing the time and cost required to bring life-saving medications to market from an average of ten to fifteen years down to just a few years. Clinical trial optimization through machine learning is also helping researchers identify the most promising drug candidates and patient populations, thereby increasing the likelihood of successful outcomes while minimizing risks to participants.

The education sector has also witnessed significant transformation through the integration of AI technologies that promise to revolutionize how students learn and how educators teach. Adaptive learning platforms now personalize educational content based on individual student performance, learning styles, and cognitive patterns, creating customized learning pathways that can accommodate diverse needs and abilities. Natural language processing enables automated essay grading and feedback generation, providing students with immediate and detailed responses to their written work while freeing educators to focus on more complex pedagogical tasks. Intelligent tutoring systems provide one-on-one instruction at scale, offering patient and consistent guidance that can supplement traditional classroom teaching. However, these developments also raise important questions about data privacy, algorithmic bias, the digital divide, and the potential displacement of human educators whose expertise and emotional intelligence remain irreplaceable in many educational contexts.

In the financial industry, AI algorithms are revolutionizing trading strategies, risk assessment, fraud detection, and customer service in ways that are reshaping the entire landscape of modern finance. High-frequency trading systems execute thousands of transactions per second based on complex mathematical models that analyze market conditions, news sentiment, and historical patterns to identify profitable opportunities. Credit scoring algorithms analyze vast datasets encompassing traditional financial metrics alongside alternative data sources to make more nuanced and inclusive lending decisions. Anomaly detection systems identify potentially fraudulent transactions in real-time, protecting consumers and financial institutions from increasingly sophisticated criminal schemes. Robo-advisors are democratizing access to investment management services, providing personalized portfolio recommendations to individuals who might not otherwise have access to professional financial guidance.

The entertainment industry has enthusiastically embraced AI for content recommendation, procedural generation, and even creative endeavors such as music composition, visual art creation, and screenplay writing. Streaming platforms use sophisticated recommendation engines powered by collaborative filtering and deep learning to suggest content tailored to individual preferences, viewing habits, and contextual factors such as time of day and device type. Game developers employ AI to create dynamic and responsive virtual worlds populated by non-player characters that exhibit increasingly realistic and unpredictable behaviors. In the realm of creative arts, generative AI models can produce original music compositions, visual artworks, and written content that challenge our traditional understanding of creativity and authorship, raising profound philosophical questions about the nature of artistic expression and intellectual property.

The transportation sector stands on the brink of a revolutionary transformation driven by autonomous vehicle technology, which promises to fundamentally alter how people and goods move through the world. Self-driving cars, trucks, and delivery vehicles are being developed and tested by numerous companies, each pursuing slightly different technological approaches but sharing the common goal of creating safer, more efficient, and more accessible transportation systems. The potential benefits are enormous, including dramatic reductions in traffic accidents caused by human error, decreased congestion through optimized routing and platooning, improved accessibility for elderly and disabled individuals, and reduced environmental impact through more efficient driving patterns and the facilitation of shared mobility services.

The manufacturing sector has been transformed by the integration of AI-powered robotics, predictive maintenance systems, and quality control mechanisms that are collectively driving what many observers describe as the fourth industrial revolution. Smart factories equipped with interconnected sensors and AI systems can monitor production processes in real-time, automatically adjusting parameters to optimize output quality and minimize waste. Predictive maintenance algorithms analyze equipment sensor data to anticipate failures before they occur, reducing downtime and extending the operational lifespan of expensive machinery. Computer vision systems inspect products with superhuman speed and accuracy, identifying defects that might escape human quality control inspectors and ensuring consistent product quality across large production runs.

Despite these remarkable achievements across virtually every sector of the economy and society, the proliferation of AI technology has also given rise to significant ethical concerns that demand urgent attention and thoughtful resolution. Questions about algorithmic transparency and explainability continue to challenge researchers who struggle to make complex neural network decisions interpretable to human stakeholders. Issues of accountability arise when AI systems make errors or produce harmful outcomes, as existing legal and regulatory frameworks were not designed to address the unique challenges posed by autonomous decision-making systems. Fairness and bias remain critical areas of investigation, as numerous studies have demonstrated that AI systems can perpetuate, amplify, or even create new forms of discrimination based on race, gender, socioeconomic status, and other protected characteristics.

The impact of AI on employment and the future of work represents perhaps the most consequential and contentious aspect of the ongoing technological revolution. While some analysts predict massive job displacement as machines become capable of performing an ever-expanding range of tasks currently performed by human workers, others argue that AI will primarily augment human capabilities rather than replace them, creating new categories of employment that we cannot yet envision. Historical precedent suggests that technological revolutions ultimately create more jobs than they destroy, but the pace and scale of AI-driven automation may challenge this pattern in unprecedented ways. The transition period is likely to be particularly difficult for workers in routine cognitive and manual occupations, necessitating significant investments in education, retraining programs, and social safety nets to ensure that the benefits of AI are broadly shared rather than concentrated among a privileged few.

Environmental applications of artificial intelligence offer both tremendous promise and important cautionary lessons about the complex relationship between technology and sustainability. AI systems are being deployed to optimize energy consumption in buildings and industrial processes, improve the efficiency of renewable energy systems, monitor deforestation and biodiversity loss through satellite imagery analysis, and develop more accurate climate models that can inform policy decisions. However, the computational resources required to train and operate large AI models consume significant amounts of energy, raising questions about the net environmental impact of these technologies and highlighting the importance of developing more energy-efficient algorithms and hardware.

Looking toward the future, the trajectory of AI development suggests that we are still in the relatively early stages of a transformation that will continue to accelerate and expand in scope for decades to come. Advances in areas such as artificial general intelligence, quantum computing, brain-computer interfaces, and synthetic biology promise to push the boundaries of what is possible even further, creating opportunities and challenges that we can barely begin to imagine. The decisions we make today about how to develop, deploy, regulate, and govern AI technologies will have profound and lasting consequences for future generations, making it imperative that we approach these questions with wisdom, humility, and a deep commitment to human values and the common good.
LONGEOF

echo "========== 1. 并发 DETECT 测试（同时提交 5 个） =========="
for i in $(seq 1 5); do
  curl -s -X POST "$BASE/detect" \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"Concurrent test $i: Artificial intelligence has transformed the way we interact with technology. Machine learning algorithms can now process vast amounts of data in seconds. The implications of these advancements are far-reaching and profound. Many researchers believe we are on the cusp of a new technological revolution that will reshape every aspect of human civilization.\"}" &
done
wait
echo ""
echo ""

echo "========== 2. 长文本 DETECT 测试（2000+ 词） =========="
# 用 python 做 JSON 转义，避免 shell 引号问题
ESCAPED_TEXT=$(python3 -c "import json,sys; print(json.dumps(sys.stdin.read().strip()))" <<< "$LONG_TEXT")
curl -s -X POST "$BASE/detect" \
  -H "Content-Type: application/json" \
  -d "{\"text\":$ESCAPED_TEXT}"
echo ""
echo ""

echo "========== 3. 短文本 DETECT 测试（一句话） =========="
curl -s -X POST "$BASE/detect" \
  -H "Content-Type: application/json" \
  -d '{"text":"Hello world this is a simple test."}'
echo ""
echo ""

echo "========== 4. 提交 HUMANIZE 任务 =========="
curl -s -X POST "$BASE/process" \
  -H "Content-Type: application/json" \
  -d '{"text":"Artificial intelligence has become increasingly prevalent in modern society. The technology continues to evolve at an unprecedented pace, transforming industries and reshaping how we live and work. Many experts predict that AI will fundamentally alter the nature of employment within the next decade."}'
echo ""
echo ""

echo "========== 5. 长文本 HUMANIZE 任务 =========="
curl -s -X POST "$BASE/process" \
  -H "Content-Type: application/json" \
  -d "{\"text\":$ESCAPED_TEXT}"
echo ""
echo ""

echo "========== 6. 空文本测试（应该报错） =========="
curl -s -X POST "$BASE/detect" \
  -H "Content-Type: application/json" \
  -d '{"text":""}'
echo ""
echo ""

echo ""
echo "========== 等待 5 秒后查看任务状态 =========="
sleep 5

echo "========== 7. 查看所有任务列表 =========="
curl -s "$BASE/tasks" | python3 -m json.tool 2>/dev/null || curl -s "$BASE/tasks"
echo ""

echo "========== 8. 按类型筛选 DETECT =========="
curl -s "$BASE/tasks?taskType=DETECT&page=1&size=20" | python3 -m json.tool 2>/dev/null || curl -s "$BASE/tasks?taskType=DETECT&page=1&size=20"
echo ""

echo "========== 9. 按类型筛选 HUMANIZE =========="
curl -s "$BASE/tasks?taskType=HUMANIZE" | python3 -m json.tool 2>/dev/null || curl -s "$BASE/tasks?taskType=HUMANIZE"
echo ""

echo "=========================================="
echo "测试完成！"
echo ""
echo "后续操作："
echo "  # 等 60 秒后再查，看任务是否跑完"
echo "  curl -s $BASE/tasks | python3 -m json.tool"
echo ""
echo "  # 查看某个任务的完整详情（含 sentencesJson）"
echo "  curl -s $BASE/tasks/2 | python3 -m json.tool"
echo ""
echo "  # 并发压力测试（同时 10 个）"
echo "  for i in \$(seq 1 10); do curl -s -X POST $BASE/detect -H 'Content-Type: application/json' -d '{\"text\":\"Stress test number '\$i': AI technology is rapidly evolving.\"}' & done; wait"
echo "=========================================="
