import React from 'react';
import { Tag, Tooltip } from 'antd';
import { useIntl } from '@umijs/max';

/** Backend constant `Skill.ORIGIN_AGENT_PROMOTED`; anything else renders as a human skill. */
export const ORIGIN_AGENT_PROMOTED = 'agent_promoted';

interface SkillOriginTagProps {
  origin?: string;
  /** Session the agent proposed the skill in. Dropped by the API when null. */
  originRef?: string;
}

const SkillOriginTag: React.FC<SkillOriginTagProps> = ({ origin, originRef }) => {
  const intl = useIntl();
  if (origin === ORIGIN_AGENT_PROMOTED) {
    const tag = <Tag color="purple">{intl.formatMessage({ id: 'pages.skill.origin.agent', defaultMessage: 'Agent promoted' })}</Tag>;
    if (!originRef) {
      return tag;
    }
    return (
      <Tooltip
        title={intl.formatMessage({
          id: 'pages.skill.origin.session',
          defaultMessage: 'Proposed in session {sessionId}',
          values: { sessionId: originRef },
        })}
      >
        {tag}
      </Tooltip>
    );
  }
  return <Tag>{intl.formatMessage({ id: 'pages.skill.origin.human', defaultMessage: 'Human' })}</Tag>;
};

export default SkillOriginTag;
