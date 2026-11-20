"""文件职责：复用C2原生UML工厂生成满血小样两张图，校验源码与布局。
协作关系：读取比较小样，输出独立mdj，不重写原42图或正式后端。
"""
from pathlib import Path
import importlib.util
import json
import sys

HERE = Path(__file__).resolve().parent
C2 = HERE.parents[1]
sys.path.insert(0, str(C2/'docs/design'))
import build_staruml as uml


def sample_source(version, qualified):
    """每个图中类型都必须存在于已编译的小样代码。"""
    path = C2/'三模型比较/小样/src/main/java'/Path(*qualified.split('.')).with_suffix('.java')
    if not path.is_file():
        raise ValueError(f'源码不存在: {qualified}')
    return path


def main():
    """复用类框避让和原生时序执行条检查，输出两张独立图。"""
    uml.source = sample_source
    uml.HERE = HERE
    uml.CATALOG.clear()
    project = uml.item('Project', name='C2 三模型比较 · 满血内存小样', ownedElements=[])
    model = uml.item('UMLModel', project, name='full-sample', ownedElements=[])
    project['ownedElements'].append(model)
    d = uml.ClassDiagram(model, 'full', '01-full-class', 'sample.addInheritance',
                         '领域对象主导两个窄端口协作；内存无数据库事务。')
    d.add('domain', 'comparison.FullRoleHierarchy', 'C',
          [('store', 'InheritanceStore'), ('policy', 'GrantPolicy'), ('parents', 'Map<Long, Set<Long>>')],
          [('addInheritance', '', ''), ('inheritedRoles', '', ''), ('dependingRoles', '', '')])
    d.add('main', 'comparison.ExampleMain', 'L', ops=[('main', '', '')])
    d.add('store', 'comparison.InheritanceStore', 'R', interface=True,
          ops=[('lockWrites', '', ''), ('role', '', ''), ('load', '', ''), ('save', '', '')])
    d.add('policy', 'comparison.GrantPolicy', 'T', interface=True,
          ops=[('requireManaged', '', ''), ('requireGrant', '', '')])
    d.add('memory', 'comparison.InMemoryInheritanceStore', 'BR', ops=[('load', '', ''), ('save', '', '')])
    d.link('main', 'domain')
    d.link('main', 'memory')
    d.link('domain', 'store', 'store')
    d.link('domain', 'policy', 'policy')
    d.link('memory', 'store', realization=True)
    d.diagram['ownedViews'].append(uml.view('UMLTextView', d.diagram, left=90, top=1450,
        width=2400, height=110, text='本小样没有Service：以实际领域用例入口居中，避免空转发层。事务由外层界定；内存不提供数据库锁或回滚保证。', wordWrap=True))
    d.finish()
    s = uml.Sequence(model, 'full', '02-full-sequence', 'sample.addInheritance',
                     '成功主链，异常不保存；省略纯集合操作和immutable辅助转换。')
    for key, name, qualified in [('obj', 'obj', None), ('domain', 'hierarchy', 'comparison.FullRoleHierarchy'),
                                 ('store', 'store', 'comparison.InMemoryInheritanceStore'),
                                 ('policy', 'policy', 'comparison.GrantPolicy')]:
        s.life(key, name, qualified, label='调用者')
    s.life('rules', '', 'comparison.FullRoleHierarchy', static=True)
    s.begin('外层传入可信身份；内存演示无真实事务')
    outer = s.call('obj', 'domain', 'addInheritance(actor, senior, junior)', duration=1200)
    s.call('domain', 'store', 'lockWrites()  // 内存不证明并发锁')
    s.call('domain', 'store', 'receiving = role(senior)')
    s.call('domain', 'store', 'providing = role(junior)  // 非自继承')
    s.call('domain', 'policy', 'requireManaged(actor, receiving)')
    s.call('domain', 'policy', 'requireManaged(actor, providing)')
    s.local('domain', '同归属且非自继承；失败直接拒绝，不保存')
    s.call('domain', 'store', 'before = load()')
    s.local('domain', '已有直接边则返回UNCHANGED；以下为首次合法新增')
    s.call('domain', 'rules', 'reachable(before, Set.of(junior))')
    s.local('domain', '若可达senior则CYCLE；原关系不变')
    s.call('domain', 'policy', 'requireGrant(actor, receiving, providing, immutable(before))')
    s.call('domain', 'store', 'save(senior, junior)')
    s.local('domain', '保存成功后更新parents，返回ADDED；保存失败不提前改图')
    s.note('本图是隔离内存小样，不代表HTTP、MySQL并发或完整组织重授。实际拒绝从抛出方返回调用者；本成功主链不穷举异常。')
    outer["subViews"][0]["height"] = s.y - outer["subViews"][0]["top"] + 20
    s.finish()
    ids, refs = uml.check_refs(project)
    (HERE/'exports').mkdir(exist_ok=True)
    (HERE/'三模型比较.mdj').write_text(json.dumps(project, ensure_ascii=False, indent=2), encoding='utf-8')
    (HERE/'图件索引.json').write_text(json.dumps(uml.CATALOG, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS: 2 diagrams; {ids} elements; {refs} references; source symbols/layout/activations checked')

if __name__ == '__main__':
    main()
